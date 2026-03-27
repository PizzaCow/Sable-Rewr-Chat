package ca.rewr.sable;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
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

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraImageUri;
    private String notificationShimJs;
    private boolean pageReady = false;
    private String pendingNavigationRoomId = null;
    private String pendingNavigationUserId = null;
    private String pendingDeepLinkUrl = null;

    // Deferred WebRTC permission grant — held until Android permission result arrives
    private PermissionRequest pendingPermissionRequest = null;

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
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), perms -> {
            // If we have a pending WebRTC permission request, resolve it now
            if (pendingPermissionRequest != null) {
                boolean allGranted = true;
                for (Boolean granted : perms.values()) {
                    if (!granted) { allGranted = false; break; }
                }
                if (allGranted) {
                    pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                } else {
                    pendingPermissionRequest.deny();
                }
                pendingPermissionRequest = null;
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        notificationShimJs = loadAsset("notification_shim.js");

        webView = findViewById(R.id.webview);
        setupWebView();

        webView.loadUrl(Config.SABLE_URL);

        // Queue room navigation if launched from notification or deep link
        if (getIntent() != null) {
            if (!handleDeepLinkIntent(getIntent()) && getIntent().hasExtra("room_id")) {
                pendingNavigationRoomId = getIntent().getStringExtra("room_id");
                pendingNavigationUserId = getIntent().getStringExtra("user_id");
            }
        }

        ContextCompat.startForegroundService(this, new Intent(this, SyncService.class));

        // Initialize FCM token and register pusher if we have a session
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token -> {
                TokenStore ts = new TokenStore(this);
                ts.saveFcmToken(token);
                if (ts.hasSession() && !ts.isFcmPusherRegistered()) {
                    FcmPushService.registerPusherFromContext(this, ts, token);
                }
            });

        // Only request notification permission upfront — mic/camera requested on demand
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

        // SSO login callback: https://rewr.chat/login/rewr.ca?loginToken=...
        if ("https".equals(scheme)
                && (Config.HOST_REWR_CHAT.equals(host) || Config.HOST_CHAT.equals(host))
                && data.getPath() != null && data.getPath().startsWith("/login/")) {
            if (pageReady) {
                webView.loadUrl(uriStr);
            } else {
                pendingDeepLinkUrl = uriStr;
            }
            return true;
        }

        if ("https".equals(scheme) && Config.HOST_DEEP_LINK.equals(host)) {
            if (pageReady) {
                webView.loadUrl(uriStr);
            } else {
                pendingDeepLinkUrl = uriStr;
            }
            return true;
        }

        if (Config.CALLBACK_SCHEME.equals(scheme) && Config.CALLBACK_HOST.equals(host)) {
            if (pageReady) {
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
            String ssp = data.getSchemeSpecificPart();
            if (ssp != null) {
                String target = null;
                if (ssp.startsWith("r/")) {
                    String alias = ssp.substring(2);
                    target = "https://" + Config.HOST_DEEP_LINK + "/#/#" + alias;
                } else if (ssp.startsWith("u/")) {
                    String userId = ssp.substring(2);
                    target = "https://" + Config.HOST_DEEP_LINK + "/#/@" + userId;
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

    /**
     * Extracts a filename from a Content-Disposition header value.
     * Falls back to a timestamped name if extraction fails.
     */
    static String extractFilename(String contentDisposition) {
        if (contentDisposition != null) {
            int fnIdx = contentDisposition.indexOf("filename=");
            if (fnIdx >= 0) {
                return contentDisposition.substring(fnIdx + 9).replaceAll("[\"']", "").trim();
            }
        }
        return "download_" + System.currentTimeMillis();
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
        cookieManager.setAcceptThirdPartyCookies(webView, false);

        WebNotificationInterface notifInterface = new WebNotificationInterface(this);
        notifInterface.setWebView(webView);
        webView.addJavascriptInterface(notifInterface, "AndroidNotifications");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                if (url != null && url.startsWith("blob:")) {
                    String safeUrl = url.replace("\\", "\\\\").replace("'", "\\'");
                    String safeMime = (mimetype != null ? mimetype : "application/octet-stream")
                            .replace("\\", "\\\\").replace("'", "\\'");
                    String safeFilename = extractFilename(contentDisposition)
                            .replace("\\", "\\\\").replace("'", "\\'");

                    String js = "(function() {" +
                        "  var xhr = new XMLHttpRequest();" +
                        "  xhr.open('GET', '" + safeUrl + "', true);" +
                        "  xhr.responseType = 'blob';" +
                        "  xhr.onload = function() {" +
                        "    var reader = new FileReader();" +
                        "    reader.onloadend = function() {" +
                        "      AndroidNotifications.onBlobDownload(reader.result, '" + safeMime + "', '" + safeFilename + "');" +
                        "    };" +
                        "    reader.readAsDataURL(xhr.response);" +
                        "  };" +
                        "  xhr.send();" +
                        "})();";
                    webView.evaluateJavascript(js, null);
                } else {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    String filename = extractFilename(contentDisposition);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                    request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                    request.addRequestHeader("User-Agent", userAgent);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) dm.enqueue(request);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri.toString();
                String scheme = uri.getScheme();
                String host = uri.getHost() != null ? uri.getHost() : "";

                // account.rewr.ca (MAS) — always open in system browser
                if (Config.HOST_ACCOUNT.equals(host)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception ignored) {}
                    return true;
                }

                // matrix.rewr.ca SSO redirect endpoint → open in system browser
                if (Config.HOST_MATRIX.equals(host) && url.contains("/login/sso/")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (Exception ignored) {}
                    return true;
                }

                // Allow all rewr.ca / rewr.chat domains in-WebView
                if (url.contains("rewr.ca") || url.contains("rewr.chat")) return false;
                // Allow custom SSO callback scheme in-WebView
                if (Config.CALLBACK_SCHEME.equals(scheme)) return false;

                // Everything else → system browser
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (notificationShimJs != null && !notificationShimJs.isEmpty()) {
                    view.evaluateJavascript(notificationShimJs, null);
                }
                view.postDelayed(() -> {
                    pageReady = true;
                    if (pendingDeepLinkUrl != null) {
                        String deepLink = pendingDeepLinkUrl;
                        pendingDeepLinkUrl = null;
                        webView.loadUrl(deepLink);
                    } else if (pendingNavigationRoomId != null) {
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
                // Determine which Android permissions are needed
                boolean needsMic = false;
                boolean needsCamera = false;
                for (String res : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) needsMic = true;
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) needsCamera = true;
                }

                boolean micGranted = !needsMic || ContextCompat.checkSelfPermission(
                    MainActivity.this, Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
                boolean cameraGranted = !needsCamera || ContextCompat.checkSelfPermission(
                    MainActivity.this, Manifest.permission.CAMERA)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;

                if (micGranted && cameraGranted) {
                    // Already have all needed permissions — grant immediately
                    request.grant(request.getResources());
                } else {
                    // Store the request and ask for permissions — grant/deny in callback
                    pendingPermissionRequest = request;
                    java.util.List<String> needed = new java.util.ArrayList<>();
                    if (!micGranted) needed.add(Manifest.permission.RECORD_AUDIO);
                    if (!cameraGranted) needed.add(Manifest.permission.CAMERA);
                    permissionLauncher.launch(needed.toArray(new String[0]));
                }
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
