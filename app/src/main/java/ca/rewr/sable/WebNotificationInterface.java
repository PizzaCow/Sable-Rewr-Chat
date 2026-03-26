package ca.rewr.sable;

import android.content.Context;
import android.webkit.JavascriptInterface;

public class WebNotificationInterface {

    private final NotificationHelper helper;
    private final TokenStore tokenStore;
    private final Context context;

    public WebNotificationInterface(Context context) {
        this.context = context.getApplicationContext();
        this.helper = new NotificationHelper(context);
        this.tokenStore = new TokenStore(context);
    }

    @JavascriptInterface
    public void showNotification(String title, String body, String tag) {
        if (title == null) title = "Sable";
        if (body == null) body = "";
        if (tag == null) tag = String.valueOf(System.currentTimeMillis());
        helper.showNotification(title, body, tag);
    }

    @JavascriptInterface
    public void closeNotification(String tag) {
        if (tag != null) helper.clearNotification(tag);
    }

    /** Called from the JS shim when it detects a Matrix access token */
    @JavascriptInterface
    public void saveSession(String accessToken, String homeserver) {
        if (accessToken != null && !accessToken.isEmpty()) {
            boolean wasEmpty = !tokenStore.hasSession();
            tokenStore.saveSession(accessToken, homeserver != null ? homeserver : "https://matrix.rewr.ca");
            if (wasEmpty) {
                // Debug: confirm token was captured
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> android.widget.Toast.makeText(
                    context, "Sable: sync connected ✓", android.widget.Toast.LENGTH_SHORT).show());
            }
        }
    }
}
