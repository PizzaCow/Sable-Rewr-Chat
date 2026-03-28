package ca.rewr.sable;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.OutputStream;

public class WebNotificationInterface {

    private static final String TAG = "WebNotifInterface";

    private final NotificationHelper helper;
    private final TokenStore tokenStore;
    private final Context context;
    private WebView webView;

    public WebNotificationInterface(Context context) {
        this.context = context.getApplicationContext();
        this.helper = new NotificationHelper(context);
        this.tokenStore = new TokenStore(context);
    }

    /** Set the WebView reference so we can check the current URL for origin validation. */
    public void setWebView(WebView webView) {
        this.webView = webView;
    }

    /**
     * Cache the last known URL on the main thread so the JS bridge thread
     * (which runs @JavascriptInterface callbacks) can check it safely.
     * WebView.getUrl() is only reliable on the main thread.
     */
    private volatile String lastKnownUrl;

    /** Call from WebViewClient.onPageFinished / onPageStarted on the main thread. */
    public void updateUrl(String url) {
        this.lastKnownUrl = url;
    }

    /**
     * Checks whether the last known WebView URL belongs to a trusted origin.
     * Uses a cached URL rather than calling webView.getUrl() which is
     * unreliable from the @JavascriptInterface background thread.
     */
    private boolean isTrustedOrigin() {
        String url = lastKnownUrl;
        if (url == null) {
            // If we haven't cached a URL yet, try the WebView directly as fallback.
            // This may return null on the JS thread, so default to true for the
            // initial page load (the WebView only loads our own SABLE_URL).
            if (webView != null) {
                try { url = webView.getUrl(); } catch (Exception e) { /* ignore */ }
            }
            if (url == null) {
                // No URL available — allow it. The WebView only loads our trusted URL,
                // and blocking here would break token saving entirely.
                Log.w(TAG, "isTrustedOrigin: no URL cached, allowing (initial load)");
                return true;
            }
        }
        for (String origin : Config.TRUSTED_ORIGINS) {
            if (url.startsWith(origin)) return true;
        }
        return false;
    }

    @JavascriptInterface
    public void showNotification(String title, String body, String tag) {
        if (title == null) title = Config.APP_DISPLAY_NAME;
        if (body == null) body = "";
        if (tag == null) tag = String.valueOf(System.currentTimeMillis());
        helper.showNotification(title, body, tag);
    }

    @JavascriptInterface
    public void showRoomNotification(String title, String body, String tag, String roomId, String userId, String eventId) {
        if (title == null) title = Config.APP_DISPLAY_NAME;
        if (body == null) body = "";
        if (tag == null) tag = String.valueOf(System.currentTimeMillis());
        if (roomId == null) roomId = "";
        if (userId == null) userId = "";
        helper.showRoomNotification(title, body, tag, roomId, userId, eventId);
    }

    @JavascriptInterface
    public void saveOwnUserId(String userId) {
        if (!isTrustedOrigin()) {
            Log.w(TAG, "saveOwnUserId blocked — untrusted origin");
            return;
        }
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

    /**
     * Called from JS when a blob URL download is intercepted.
     * dataUrl is a base64 data URL like "data:image/png;base64,..."
     */
    @JavascriptInterface
    public void onBlobDownload(String dataUrl, String mimeType, String filename) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return;
        if (mimeType == null || mimeType.isEmpty()) mimeType = "application/octet-stream";
        if (filename == null || filename.isEmpty()) filename = "download_" + System.currentTimeMillis();

        final String finalMime = mimeType;
        final String finalName = filename;

        try {
            // Strip "data:<mime>;base64," prefix
            int commaIdx = dataUrl.indexOf(',');
            if (commaIdx < 0) return;
            String base64Data = dataUrl.substring(commaIdx + 1);
            byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);

            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, finalName);
            values.put(MediaStore.Downloads.MIME_TYPE, finalMime);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            ContentResolver resolver = context.getContentResolver();
            Uri collection;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            } else {
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            }
            Uri itemUri = resolver.insert(collection, values);
            if (itemUri == null) return;

            try (OutputStream os = resolver.openOutputStream(itemUri)) {
                if (os != null) os.write(bytes);
            }

            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(itemUri, values, null, null);

            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "Downloaded: " + finalName, Toast.LENGTH_SHORT).show());

        } catch (Exception e) {
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Called from the JS shim when it detects a Matrix access token.
     * Only accepts calls from trusted origins to prevent rogue JS injection.
     */
    @JavascriptInterface
    public void saveSession(String accessToken, String homeserver) {
        if (!isTrustedOrigin()) {
            Log.w(TAG, "saveSession blocked — untrusted origin");
            return;
        }
        if (accessToken != null && !accessToken.isEmpty()) {
            String hs = homeserver != null ? homeserver : Config.DEFAULT_HOMESERVER;
            tokenStore.saveSession(accessToken, hs);
            // Register FCM pusher if token already available
            String fcmToken = tokenStore.getFcmToken();
            if (fcmToken != null && !fcmToken.isEmpty()) {
                FcmPushService.registerPusherFromContext(context, tokenStore, fcmToken);
            }
        }
    }
}
