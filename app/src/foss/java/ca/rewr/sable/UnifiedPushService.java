package ca.rewr.sable;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.MessagingReceiver;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Receives UnifiedPush messages and fires local Matrix notifications.
 *
 * UnifiedPush delivers the actual push content (not a silent ping), so we parse
 * the message body directly from the push payload without a round-trip to Synapse.
 *
 * Registration flow:
 *   1. App calls UnifiedPush.tryUseCurrentOrDefaultDistributor() + register() on startup.
 *   2. Distributor calls onNewEndpoint() with a push URL.
 *   3. We register that URL as an HTTP pusher with Synapse.
 *   4. Synapse POSTs notifications to the URL (via the distributor).
 *   5. onMessage() fires; we show a local notification.
 */
public class UnifiedPushService extends MessagingReceiver {

    private static final String TAG = "SableUP";

    public UnifiedPushService() {
        super();
    }

    @Override
    public void onNewEndpoint(Context context, PushEndpoint pushEndpoint, String instance) {
        String endpoint = pushEndpoint.getUrl();
        Log.i(TAG, "UP endpoint: " + endpoint);
        TokenStore ts = new TokenStore(context);
        ts.saveUpEndpoint(endpoint);
        ts.setUpPusherRegistered(false);
        if (ts.hasSession()) {
            registerPusher(context, ts, endpoint);
        }
    }

    @Override
    public void onRegistrationFailed(Context context, FailedReason reason, String instance) {
        Log.w(TAG, "UP registration failed: " + reason);
    }

    @Override
    public void onUnregistered(Context context, String instance) {
        Log.i(TAG, "UP unregistered");
        TokenStore ts = new TokenStore(context);
        ts.saveUpEndpoint(null);
        ts.setUpPusherRegistered(false);
    }

    @Override
    public void onMessage(Context context, PushMessage pushMessage, String instance) {
        byte[] messageBytes = pushMessage.getContent();
        String payload;
        try {
            payload = new String(messageBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Failed to decode UP message");
            return;
        }
        Log.d(TAG, "UP message received");

        TokenStore ts = new TokenStore(context);
        if (!ts.hasSession()) return;

        try {
            JSONObject json = new JSONObject(payload);
            // Sygnal-format payload: { notification: { ... } }
            JSONObject notif = json.optJSONObject("notification");
            if (notif == null) notif = json; // fallback: flat payload

            String roomId = notif.optString("room_id", null);
            String eventId = notif.optString("event_id", null);
            String sender = notif.optString("sender", null);
            String roomName = notif.optString("room_name", null);
            String senderDisplayName = notif.optString("sender_display_name", null);
            String type = notif.optString("type", null);
            String userCountStr = notif.optString("user_count", null);

            if (roomId == null || roomId.isEmpty()) return;

            String ownUserId = ts.getOwnUserId();
            if (sender != null && !ownUserId.isEmpty() && sender.equals(ownUserId)) return;
            if (MainActivity.isInForeground && roomId.equals(MainActivity.currentRoomId)) return;

            boolean isEncrypted = "m.room.encrypted".equals(type);

            NotificationHelper notifHelper = new NotificationHelper(context);
            MatrixProfileCache profileCache = new MatrixProfileCache(context);

            if (roomName == null || roomName.isEmpty()) roomName = profileCache.getRoomName(roomId);
            if (senderDisplayName == null || senderDisplayName.isEmpty()) {
                senderDisplayName = sender != null ? profileCache.getDisplayName(sender) : "Someone";
            }

            android.graphics.Bitmap senderAvatar = sender != null ? profileCache.getAvatar(sender) : null;
            android.graphics.Bitmap roomAvatar = profileCache.getRoomAvatar(roomId);

            // Fetch message body from Synapse if not encrypted and we have event ID
            String body = null;
            if (eventId != null && !isEncrypted) {
                body = fetchEventBody(ts, roomId, eventId);
            }

            boolean isDm;
            if (userCountStr != null && !userCountStr.isEmpty()) {
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

        } catch (Exception e) {
            Log.e(TAG, "Failed to process UP message: " + e.getMessage());
        }
    }

    // ── Pusher registration ────────────────────────────────────────────────────

    static void registerPusher(Context context, TokenStore ts, String endpoint) {
        new Thread(() -> {
            try {
                JSONObject data = new JSONObject();
                data.put("url", endpoint);
                data.put("format", "event_id_only");

                JSONObject body = new JSONObject();
                body.put("kind", "http");
                body.put("app_id", "ca.rewr.sable.up"); // distinct app_id from FCM
                body.put("app_display_name", "Rewr.chat (FOSS)");
                body.put("device_display_name", "Android (UnifiedPush)");
                body.put("pushkey", endpoint);
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
                Log.i(TAG, "UP pusher registration: " + code);
                if (code == 200) {
                    ts.setUpPusherRegistered(true);
                }
            } catch (Exception e) {
                Log.e(TAG, "UP pusher registration failed: " + e.getMessage());
            }
        }).start();
    }

    private String fetchEventBody(TokenStore ts, String roomId, String eventId) {
        try {
            String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
            String encodedEvent = URLEncoder.encode(eventId, "UTF-8");
            URL url = new URL(ts.getHomeserver()
                + "/_matrix/client/v3/rooms/" + encodedRoom + "/event/" + encodedEvent);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + ts.getAccessToken());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
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
