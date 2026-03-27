package ca.rewr.sable;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String SABLE_URL = "https://chat.rewr.ca";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraImageUri;
    private String notificationShimJs;
    private boolean pageReady = false;
    private String pendingNavigationRoomId = null;
    private String pendingNavigationUserId = null;
    private String pendingDeepLinkUrl = null;

    // Track foreground state and current room for notification suppression
    public static boolean isInForeground = false;
    public static String currentRoomId = null;

    private final ActivityResultLauncher<Intent> fileChooserLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Uri[] results = null;
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String dataString = result.getData().getDataString();
                if (dataString != null) results = new Uri[]{Uri.parse(dataString)};
            } else if (cameraImageUri != null) {
                results = new Uri[]{cameraImageUri};
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        });

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), perms -> {});

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        notificationShimJs = loadAsset("notification_shim.js");

        webView = findViewById(R.id.webview);
        setupWebView();

        webView.loadUrl(SABLE_URL);

        // Queue room navigation if launched from notification or deep link
        if (getIntent() != null) {
            if (!handleDeepLinkIntent(getIntent()) && getIntent().hasExtra("room_id")) {
                pendingNavigationRoomId = getIntent().getStringExtra("room_id");
                pendingNavigationUserId = getIntent().getStringExtra("user_id");
            }
        }

        // Initialize push backend (FCM in full flavor, UnifiedPush in foss flavor)
        new PushProviderImpl().init(this, new TokenStore(this));

        // Only request notification permission upfront — mic/storage requested on demand
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isInForeground = true;
        // Clear all notifications + badge when user opens the app
        new NotificationHelper(this).clearAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isInForeground = false;
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;

        // Handle deep link intents (to.rewr.chat, matrix:, ca.rewr.sable://callback)
        if (handleDeepLinkIntent(intent)) return;

        // Notification tap: navigate to room
        String roomId = intent.getStringExtra("room_id");
        if (roomId == null) return;
        String userId = intent.getStringExtra("user_id");
        navigateToRoom(roomId, userId);
    }

    /**
     * Handles incoming deep link intents.
     * Returns true if the intent was a deep link and was handled (or queued).
     */
    private boolean handleDeepLinkIntent(Intent intent) {
        Uri data = intent.getData();
        if (data == null) return false;

        String scheme = data.getScheme();
        String host = data.getHost();
        String uriStr = data.toString();

        if ("https".equals(scheme) && "to.rewr.chat".equals(host)) {
            // e.g. https://to.rewr.chat/#/@user:rewr.ca  or  https://to.rewr.chat/#/#room:rewr.ca
            // Just load the URL in the WebView — the to.rewr.chat page handles navigation into Sable
            if (pageReady) {
                webView.loadUrl(uriStr);
            } else {
                pendingDeepLinkUrl = uriStr;
            }
            return true;
        }

        if ("ca.rewr.sable".equals(scheme) && "callback".equals(host)) {
            // SSO OAuth redirect — pass to WebView so Sable can complete the login flow
            if (pageReady) {
                // Try to hand off to Sable's JS OAuth handler first; fall back to loading the URI
                String safeUri = uriStr.replace("\\", "\\\\").replace("'", "\\'");
                webView.evaluateJavascript(
                    "(function(){ if(window.__oauthCallback) window.__oauthCallback('" + safeUri + "'); })();",
                    null);
            } else {
                pendingDeepLinkUrl = uriStr;
            }
            return true;
        }

        if ("matrix".equals(scheme)) {
            // e.g. matrix:r/roomalias:rewr.ca  or  matrix:u/userid:rewr.ca
            String ssp = data.getSchemeSpecificPart(); // e.g. "r/roomalias:rewr.ca"
            if (ssp != null) {
                String target = null;
                if (ssp.startsWith("r/")) {
                    // Room alias or ID → open in Sable via to.rewr.chat
                    String alias = ssp.substring(2);
                    target = "https://to.rewr.chat/#/#" + alias;
                } else if (ssp.startsWith("u/")) {
                    // User ID
                    String userId = ssp.substring(2);
                    target = "https://to.rewr.chat/#/@" + userId;
                }
                if (target != null) {
                    final String url = target;
                    if (pageReady) {
                        webView.loadUrl(url);
                    } else {
                        pendingDeepLinkUrl = url;
                    }
                    return true;
                }
            }
        }

        return false;
    }

    private void navigateToRoom(String roomId, String userId) {
        currentRoomId = roomId;
        if (!pageReady) {
            pendingNavigationRoomId = roomId;
            pendingNavigationUserId = userId;
            return;
        }
        String safeRoomId = roomId.replace("\\", "\\\\").replace("'", "\\'");
        String js;
        if (userId != null && !userId.isEmpty()) {
            String safeUserId = userId.replace("\\", "\\\\").replace("'", "\\'");
            js = "(function() {" +
                "  var path = '/to/' + encodeURIComponent('" + safeUserId + "') + '/' + encodeURIComponent('" + safeRoomId + "') + '/';" +
                "  window.history.pushState({}, '', path);" +
                "})();";
        } else {
            js = "(function() {" +
                "  var path = '/home/' + encodeURIComponent('" + safeRoomId + "') + '/';" +
                "  window.history.pushState({}, '', path);" +
                "})();";
        }
        webView.evaluateJavascript(js, null);
        String afterPush = "window.dispatchEvent(new PopStateEvent('popstate', {state: window.history.state}));";
        webView.evaluateJavascript(afterPush, null);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " RewrChat/1.0");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new WebNotificationInterface(this), "AndroidNotifications");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri.toString();
                String scheme = uri.getScheme();
                // Allow rewr.ca / rewr.chat domains and our custom SSO scheme to load in-WebView
                if (url.contains("rewr.ca") || url.contains("rewr.chat")) return false;
                if ("ca.rewr.sable".equals(scheme)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // Inject fetch interceptor as early as possible so we catch tokens
                // before the React bundle captures its own fetch reference
                if (notificationShimJs != null && !notificationShimJs.isEmpty()) {
                    view.evaluateJavascript(notificationShimJs, null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Re-inject on page finished to ensure localStorage scan runs after React mounts
                if (notificationShimJs != null && !notificationShimJs.isEmpty()) {
                    view.evaluateJavascript(notificationShimJs, null);
                }
                // Mark page ready after React has had time to mount
                view.postDelayed(() -> {
                    pageReady = true;
                    // Handle any queued deep link (takes priority over room navigation)
                    if (pendingDeepLinkUrl != null) {
                        String deepLinkUrl = pendingDeepLinkUrl;
                        pendingDeepLinkUrl = null;
                        webView.loadUrl(deepLinkUrl);
                    } else if (pendingNavigationRoomId != null) {
                        // Handle any queued notification navigation
                        String roomId = pendingNavigationRoomId;
                        String userId = pendingNavigationUserId;
                        pendingNavigationRoomId = null;
                        pendingNavigationUserId = null;
                        navigateToRoom(roomId, userId);
                    }
                }, 1500);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Request mic permission on demand when WebRTC needs it
                boolean needsMic = false;
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                        needsMic = true;
                        break;
                    }
                }
                if (needsMic && ContextCompat.checkSelfPermission(
                        MainActivity.this, Manifest.permission.RECORD_AUDIO)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(new String[]{Manifest.permission.RECORD_AUDIO});
                    // Grant anyway — WebView will handle denial internally
                }
                if (ContextCompat.checkSelfPermission(
                        MainActivity.this, Manifest.permission.CAMERA)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
                }
                request.grant(request.getResources());
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                              FileChooserParams params) {
                filePathCallback = callback;
                Intent chooser = params.createIntent();
                try {
                    File photoFile = createImageFile();
                    cameraImageUri = FileProvider.getUriForFile(MainActivity.this,
                        getPackageName() + ".fileprovider", photoFile);
                    Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
                    Intent picker = Intent.createChooser(chooser, "Choose file");
                    picker.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
                    fileChooserLauncher.launch(picker);
                } catch (IOException e) {
                    fileChooserLauncher.launch(chooser);
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage msg) {
                return true;
            }
        });
    }

    private String loadAsset(String filename) {
        try {
            InputStream is = getAssets().open(filename);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private File createImageFile() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile("JPEG_" + timestamp + "_", ".jpg", storageDir);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        webView.restoreState(savedInstanceState);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
