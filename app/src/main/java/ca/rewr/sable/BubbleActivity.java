package ca.rewr.sable;

import android.os.Bundle;
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
 * Loads the Sable web app directly to the target room for quick replies.
 *
 * Shares WebView data (localStorage, IndexedDB, cookies) with MainActivity,
 * so the E2EE session and login state carry over — no extra login needed.
 */
public class BubbleActivity extends AppCompatActivity {

    public static final String EXTRA_ROOM_ID = "room_id";
    public static final String EXTRA_USER_ID = "user_id";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        setupWebView();

        String roomId = getIntent() != null ? getIntent().getStringExtra(EXTRA_ROOM_ID) : null;
        String userId = getIntent() != null ? getIntent().getStringExtra(EXTRA_USER_ID) : null;

        if (roomId != null && userId != null && !userId.isEmpty()) {
            // DM — navigate via /to/<userId>/<roomId>/
            String url = Config.SABLE_URL + "/to/"
                + android.net.Uri.encode(userId) + "/"
                + android.net.Uri.encode(roomId) + "/";
            webView.loadUrl(url);
        } else if (roomId != null) {
            // Room — navigate via /home/<roomId>/
            String url = Config.SABLE_URL + "/home/" + android.net.Uri.encode(roomId) + "/";
            webView.loadUrl(url);
        } else {
            // Fallback — just load home
            webView.loadUrl(Config.SABLE_URL);
        }
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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Inject the notification shim so the bubble can update currentRoom
                String shim = loadAsset("notification_shim.js");
                if (shim != null && !shim.isEmpty()) {
                    view.evaluateJavascript(shim, null);
                }
            }
        });

        // Minimal JS bridge — just setCurrentRoom so notification suppression works
        WebNotificationInterface notifInterface = new WebNotificationInterface(this);
        notifInterface.setWebView(webView);
        webView.addJavascriptInterface(notifInterface, "AndroidNotifications");
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
