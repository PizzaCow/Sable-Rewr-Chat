package ca.rewr.sable;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * Lightweight activity displayed inside an Android conversation bubble.
 * Loads the Sable web app then navigates to the target room via JS,
 * mirroring how MainActivity.navigateToRoom() works.
 *
 * Shares WebView data (localStorage, IndexedDB, cookies) with MainActivity,
 * so the E2EE session and login state carry over — no extra login needed.
 */
public class BubbleActivity extends AppCompatActivity {

    public static final String EXTRA_ROOM_ID = "room_id";
    public static final String EXTRA_USER_ID = "user_id";

    private WebView webView;
    private String pendingRoomId;
    private String pendingUserId;
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        pendingRoomId = getIntent() != null ? getIntent().getStringExtra(EXTRA_ROOM_ID) : null;
        pendingUserId = getIntent() != null ? getIntent().getStringExtra(EXTRA_USER_ID) : null;

        setupWebView();

        // Always load the base URL — Cinny is an SPA, direct paths don't work
        webView.loadUrl(Config.SABLE_URL);
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " RewrChat/1.0 Bubble");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);

        // Minimal JS bridge — setCurrentRoom so notification suppression works
        final WebNotificationInterface notifInterface = new WebNotificationInterface(this);
        notifInterface.setWebView(webView);
        notifInterface.updateUrl(Config.SABLE_URL);
        webView.addJavascriptInterface(notifInterface, "AndroidNotifications");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                notifInterface.updateUrl(url);
                // Inject notification shim
                String shim = loadAsset("notification_shim.js");
                if (shim != null && !shim.isEmpty()) {
                    view.evaluateJavascript(shim, null);
                }

                // Wait for the SPA to initialize, then navigate to the room
                if (!pageReady && pendingRoomId != null) {
                    // Delay to let React/Cinny hydrate — same pattern as MainActivity
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        pageReady = true;
                        navigateToRoom(pendingRoomId, pendingUserId);
                    }, 1500);
                }
            }
        });
    }

    /**
     * Navigate to a room using pushState + popstate, same as MainActivity.
     * Cinny listens for popstate events to handle client-side routing.
     */
    private void navigateToRoom(String roomId, String userId) {
        if (webView == null || roomId == null) return;

        MainActivity.currentRoomId = roomId;

        String safeRoomId = roomId.replace("\\", "\\\\").replace("'", "\\'");
        String js;
        if (userId != null && !userId.isEmpty()) {
            String safeUserId = userId.replace("\\", "\\\\").replace("'", "\\'");
            js = "(function() {" +
                "  var path = '/to/' + encodeURIComponent('" + safeUserId + "') + '/' + encodeURIComponent('" + safeRoomId + "') + '/';" +
                "  window.history.pushState({}, '', path);" +
                "  window.dispatchEvent(new PopStateEvent('popstate', {state: window.history.state}));" +
                "})();";
        } else {
            js = "(function() {" +
                "  var path = '/home/' + encodeURIComponent('" + safeRoomId + "') + '/';" +
                "  window.history.pushState({}, '', path);" +
                "  window.dispatchEvent(new PopStateEvent('popstate', {state: window.history.state}));" +
                "})();";
        }
        webView.evaluateJavascript(js, null);
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

    @Override
    protected void onResume() {
        super.onResume();
        MainActivity.isInForeground = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        MainActivity.isInForeground = false;
        MainActivity.currentRoomId = null;
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
