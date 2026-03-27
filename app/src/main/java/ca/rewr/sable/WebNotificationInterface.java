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
        if (title == null) title = "Rewr.chat";
        if (body == null) body = "";
        if (tag == null) tag = String.valueOf(System.currentTimeMillis());
        helper.showNotification(title, body, tag);
    }

    @JavascriptInterface
    public void showRoomNotification(String title, String body, String tag, String roomId, String userId, String eventId) {
        if (title == null) title = "Rewr.chat";
        if (body == null) body = "";
        if (tag == null) tag = String.valueOf(System.currentTimeMillis());
        if (roomId == null) roomId = "";
        if (userId == null) userId = "";
        helper.showRoomNotification(title, body, tag, roomId, userId, eventId);
    }

    @JavascriptInterface
    public void saveOwnUserId(String userId) {
        if (userId != null && !userId.isEmpty()) {
            tokenStore.saveOwnUserId(userId);
        }
    }

    @JavascriptInterface
    public void closeNotification(String tag) {
        if (tag != null) helper.clearNotification(tag);
    }

    /** Called from JS when the user navigates to a room */
    @JavascriptInterface
    public void setCurrentRoom(String roomId) {
        MainActivity.currentRoomId = roomId;
    }

    /** Called from the JS shim when it detects a Matrix access token */
    @JavascriptInterface
    public void saveSession(String accessToken, String homeserver) {
        if (accessToken != null && !accessToken.isEmpty()) {
            boolean wasEmpty = !tokenStore.hasSession();
            String hs = homeserver != null ? homeserver : "https://matrix.rewr.ca";
            tokenStore.saveSession(accessToken, hs);
            if (wasEmpty) {
                // Debug: confirm token was captured
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> android.widget.Toast.makeText(
                    context, "Rewr.chat: sync connected ✓", android.widget.Toast.LENGTH_SHORT).show());
            }
            // Register FCM pusher if token already available
            String fcmToken = tokenStore.getFcmToken();
            if (fcmToken != null && !fcmToken.isEmpty()) {
                FcmPushService.registerPusherFromContext(context, tokenStore, fcmToken);
            }
        }
    }
}
