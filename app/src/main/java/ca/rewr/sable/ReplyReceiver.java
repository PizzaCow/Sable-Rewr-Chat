package ca.rewr.sable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Handles inline quick-reply notification action. */
public class ReplyReceiver extends BroadcastReceiver {

    private static final String TAG = "ReplyReceiver";
    static final String ACTION = "ca.rewr.sable.REPLY";
    static final String EXTRA_ROOM_ID = "room_id";
    static final String EXTRA_NOTIF_ID = "notif_id";
    static final String KEY_TEXT_REPLY = "key_text_reply";

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
        if (remoteInput == null) return;
        CharSequence replyText = remoteInput.getCharSequence(KEY_TEXT_REPLY);
        if (replyText == null || replyText.toString().trim().isEmpty()) return;

        String roomId = intent.getStringExtra(EXTRA_ROOM_ID);
        int notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1);
        if (roomId == null) return;

        String message = replyText.toString().trim();

        // Update notification to "Sending…"
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        try {
            NotificationCompat.Builder sending = new NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID_DM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentText("Sending…")
                .setPriority(NotificationCompat.PRIORITY_LOW);
            nm.notify(notifId, sending.build());
        } catch (SecurityException ignored) {}

        TokenStore ts = new TokenStore(context);
        if (!ts.hasSession()) return;

        new Thread(() -> {
            boolean sent = false;
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String txnId = UUID.randomUUID().toString().replace("-", "");
                URL url = new URL(ts.getHomeserver()
                    + "/_matrix/client/v3/rooms/" + encodedRoom
                    + "/send/m.room.message/" + txnId);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Authorization", "Bearer " + ts.getAccessToken());
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                JSONObject body = new JSONObject();
                body.put("msgtype", "m.text");
                body.put("body", message);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                sent = (code == 200);
                Log.d(TAG, "Send message response: " + code);
            } catch (Exception e) {
                Log.w(TAG, "Failed to send message: " + e.getMessage());
            }

            // Cancel notification after short delay whether sent or not
            final boolean wasSent = sent;
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(() -> {
                nm.cancel(notifId);
                if (!wasSent) {
                    // If failed, clear so user can retry from app
                    new NotificationHelper(context).clearNotification(roomId);
                }
            }, wasSent ? 1000 : 500);
        }).start();
    }
}
