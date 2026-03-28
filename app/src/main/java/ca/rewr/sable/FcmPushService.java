package ca.rewr.sable;

import android.content.Context;
import android.util.Log;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Receives FCM push notifications from Sygnal and fires local notifications.
 *
 * Sygnal sends a "ping" (empty/minimal payload) via FCM — not the message content.
 * On receipt, we use the stored token to fetch the actual event from Synapse,
 * then display a notification.
 *
 * For privacy, Sygnal is configured with include_content: false so message
 * content never passes through Google. We fetch it directly from our homeserver.
 */
public class FcmPushService extends FirebaseMessagingService {

    private static final String TAG = "SableFCM";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Log.i(TAG, "FCM message received");

        TokenStore ts = new TokenStore(this);
        if (!ts.hasSession()) {
            Log.w(TAG, "No session, ignoring FCM push");
            return;
        }

        // Extract data from the FCM payload (Sygnal sends room_id, event_id etc in data)
        String roomId = remoteMessage.getData().get("room_id");
        String eventId = remoteMessage.getData().get("event_id");
        String sender = remoteMessage.getData().get("sender");
        String roomName = remoteMessage.getData().get("room_name");
        String senderDisplayName = remoteMessage.getData().get("sender_display_name");
        String type = remoteMessage.getData().get("type");
        String userCountStr = remoteMessage.getData().get("user_count");

        if (roomId == null || roomId.isEmpty()) {
            Log.w(TAG, "FCM push missing room_id, ignoring");
            return;
        }

        // Deduplicate: if SyncService already showed a notification for this event, skip.
        if (!ts.claimNotification(eventId)) {
            Log.d(TAG, "FCM push deduplicated (already shown by SyncService): " + eventId);
            return;
        }

        // Skip own messages
        String ownUserId = ts.getOwnUserId();
        if (sender != null && !ownUserId.isEmpty() && sender.equals(ownUserId)) return;

        // Skip if app is in foreground and user is already in this room
        if (MainActivity.isInForeground && roomId.equals(MainActivity.currentRoomId)) return;

        boolean isEncrypted = "m.room.encrypted".equals(type);

        NotificationHelper notifHelper = new NotificationHelper(this);
        MatrixProfileCache profileCache = new MatrixProfileCache(this);

        if (roomName == null || roomName.isEmpty()) roomName = profileCache.getRoomName(roomId);
        if (senderDisplayName == null || senderDisplayName.isEmpty()) {
            senderDisplayName = sender != null ? profileCache.getDisplayName(sender) : "Someone";
        }

        android.graphics.Bitmap senderAvatar = sender != null ? profileCache.getAvatar(sender) : null;
        android.graphics.Bitmap roomAvatar = profileCache.getRoomAvatar(roomId);

        // Since include_content=false, we don't have message body — fetch it from Synapse
        String body = null;
        if (eventId != null && !isEncrypted) {
            body = fetchEventBody(ts, roomId, eventId);
        }

        // Use user_count from Sygnal payload for reliable DM detection.
        // Fall back to room name heuristic: if Synapse sent a room_name it's almost certainly a group.
        boolean isDm;
        if (userCountStr != null) {
            try { isDm = Integer.parseInt(userCountStr) <= 2; }
            catch (NumberFormatException e) { isDm = (roomName == null || roomName.isEmpty()); }
        } else {
            isDm = (roomName == null || roomName.isEmpty());
        }

        notifHelper.showMessage(
            roomId,
            roomName != null ? roomName : "Message",
            senderDisplayName,
            senderAvatar, roomAvatar,
            body != null ? body : (isEncrypted ? "Encrypted message" : "New message"),
            isEncrypted, isDm, eventId
        );
    }

    @Override
    public void onNewToken(String token) {
        Log.i(TAG, "FCM token refreshed");
        // Register new token as a pusher with Synapse via Sygnal
        TokenStore ts = new TokenStore(this);
        ts.saveFcmToken(token);
        if (ts.hasSession()) {
            registerPusherFromContext(this, ts, token);
        }
    }

    /** Package-private static helper — callable from MainActivity and WebNotificationInterface. */
    static void registerPusherFromContext(Context context, TokenStore ts, String fcmToken) {
        new Thread(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("url", Config.SYGNAL_URL);

                JSONObject body = new JSONObject();
                body.put("kind", "http");
                body.put("app_id", Config.APP_ID);
                body.put("app_display_name", Config.APP_DISPLAY_NAME);
                body.put("device_display_name", "Android");
                body.put("pushkey", fcmToken);
                body.put("lang", "en");
                body.put("append", false);
                body.put("data", data);

                URL url = new URL(ts.getHomeserver() + "/_matrix/client/v3/pushers/set");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + ts.getAccessToken());
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }

                int code = conn.getResponseCode();
                conn.disconnect();
                Log.i(TAG, "Pusher registration response: " + code);
                if (code == 200) {
                    ts.setFcmPusherRegistered(true);
                }
            } catch (Exception e) {
                Log.e(TAG, "Pusher registration failed: " + e.getMessage());
            }
        }).start();
    }

    private String fetchEventBody(TokenStore ts, String roomId, String eventId) {
        try {
            String encodedRoom = java.net.URLEncoder.encode(roomId, "UTF-8");
            String encodedEvent = java.net.URLEncoder.encode(eventId, "UTF-8");
            URL url = new URL(ts.getHomeserver() + "/_matrix/client/v3/rooms/" + encodedRoom + "/event/" + encodedEvent);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + ts.getAccessToken());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                conn.disconnect();
                JSONObject event = new JSONObject(sb.toString());
                JSONObject content = event.optJSONObject("content");
                if (content != null) return content.optString("body", null);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch event body: " + e.getMessage());
        }
        return null;
    }
}
