package ca.rewr.sable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Handles "Mark as read" notification action. */
public class MarkReadReceiver extends BroadcastReceiver {

    private static final String TAG = "MarkReadReceiver";
    static final String ACTION = "ca.rewr.sable.MARK_READ";
    static final String EXTRA_ROOM_ID = "room_id";
    static final String EXTRA_EVENT_ID = "event_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        String roomId = intent.getStringExtra(EXTRA_ROOM_ID);
        String eventId = intent.getStringExtra(EXTRA_EVENT_ID);
        if (roomId == null) return;

        // Cancel notification immediately
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.cancel(NotificationHelper.notifIdForRoom(roomId));
        new NotificationHelper(context).clearNotification(roomId);

        if (eventId == null || eventId.isEmpty()) return;

        TokenStore ts = new TokenStore(context);
        if (!ts.hasSession()) return;

        new Thread(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                URL url = new URL(ts.getHomeserver()
                    + "/_matrix/client/v3/rooms/" + encodedRoom + "/read_markers");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + ts.getAccessToken());
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                JSONObject body = new JSONObject();
                body.put("m.fully_read", eventId);
                body.put("m.read", eventId);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                Log.d(TAG, "read_markers response: " + code);
            } catch (Exception e) {
                Log.w(TAG, "Failed to send read markers: " + e.getMessage());
            }
        }).start();
    }
}
