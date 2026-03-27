package ca.rewr.sable;

import android.content.Context;
import android.util.Log;
import org.unifiedpush.android.connector.MessagingReceiver;

public class UnifiedPushService extends MessagingReceiver {
    private static final String TAG = "SableUP";

    @Override
    public void onNewEndpoint(Context context, String endpoint, String instance) {
        Log.i(TAG, "New UP endpoint: " + endpoint);
        // Save endpoint and register pusher with Synapse
        TokenStore ts = new TokenStore(context);
        ts.saveUpEndpoint(endpoint);
        if (ts.hasSession()) {
            PusherRegistrar.register(context, endpoint, ts.getAccessToken(), ts.getHomeserver());
        }
        // Tell SyncService to stop sending notifications (UP handles it now)
        SyncService.upActive = true;
    }

    @Override
    public void onRegistrationFailed(Context context, String instance) {
        Log.w(TAG, "UP registration failed");
        SyncService.upActive = false;
    }

    @Override
    public void onUnregistered(Context context, String instance) {
        Log.i(TAG, "UP unregistered");
        TokenStore ts = new TokenStore(context);
        ts.saveUpEndpoint(null);
        SyncService.upActive = false;
        // Remove pusher from Synapse
        if (ts.hasSession()) {
            PusherRegistrar.unregister(context, ts.getAccessToken(), ts.getHomeserver());
        }
    }

    @Override
    public void onMessage(Context context, byte[] message, String instance) {
        // The UP message IS the Matrix push notification payload (JSON from Synapse)
        // Parse it and fire a local notification
        Log.i(TAG, "UP message received");
        try {
            org.json.JSONObject payload = new org.json.JSONObject(new String(message));
            org.json.JSONObject notification = payload.optJSONObject("notification");
            if (notification == null) return;

            String roomId = notification.optString("room_id", null);
            String roomName = notification.optString("room_name", null);
            String senderDisplayName = notification.optString("sender_display_name", null);
            String body = notification.optString("content", null);
            // content is a nested object for m.room.message
            org.json.JSONObject content = notification.optJSONObject("content");
            String msgBody = (content != null) ? content.optString("body", null) : null;
            if (msgBody != null) body = msgBody;

            String sender = notification.optString("sender", "");
            boolean isEncrypted = "m.room.encrypted".equals(notification.optString("type", ""));

            if (roomId == null || roomId.isEmpty()) return;

            TokenStore ts = new TokenStore(context);
            String ownUserId = ts.getOwnUserId();
            if (!ownUserId.isEmpty() && sender.equals(ownUserId)) return;

            NotificationHelper notifHelper = new NotificationHelper(context);
            MatrixProfileCache profileCache = new MatrixProfileCache(context);

            if (roomName == null || roomName.isEmpty()) {
                roomName = profileCache.getRoomName(roomId);
            }
            if (senderDisplayName == null || senderDisplayName.isEmpty()) {
                senderDisplayName = profileCache.getDisplayName(sender);
            }
            android.graphics.Bitmap senderAvatar = profileCache.getAvatar(sender);
            android.graphics.Bitmap roomAvatar = profileCache.getRoomAvatar(roomId);

            // Determine DM vs group from cached data (best effort)
            boolean isDm = (roomAvatar == null && !roomId.startsWith("#"));

            notifHelper.showMessage(
                roomId, roomName != null ? roomName : "Message",
                senderDisplayName != null ? senderDisplayName : sender,
                senderAvatar, roomAvatar,
                body != null ? body : (isEncrypted ? "Encrypted message" : "New message"),
                isEncrypted, isDm
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse UP message: " + e.getMessage());
        }
    }
}
