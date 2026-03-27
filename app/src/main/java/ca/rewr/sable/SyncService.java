package ca.rewr.sable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class SyncService extends Service {

    private static final String TAG = "SableSyncService";
    private static final String CHANNEL_ID = "sable_sync_service_v2";
    private static final int FOREGROUND_ID = 1001;
    // 30s long-poll timeout
    private static final int LONG_POLL_MS = 30000;

    private volatile boolean running = false;
    private Thread syncThread;
    private TokenStore tokenStore;
    private NotificationHelper notifHelper;
    private MatrixProfileCache profileCache;


    @Override
    public void onCreate() {
        super.onCreate();
        tokenStore = new TokenStore(this);
        notifHelper = new NotificationHelper(this);
        profileCache = new MatrixProfileCache(this);
        createServiceChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start as foreground briefly (required by Android), then demote to background.
        // FCM handles push — we only need the sync loop for next_batch token updates.
        // Posting a silent foreground notification then immediately removing it avoids
        // the persistent "Connected" notification in the shade.
        startForeground(FOREGROUND_ID, buildServiceNotification());
        stopForeground(STOP_FOREGROUND_REMOVE);

        if (!running) {
            running = true;
            syncThread = new Thread(this::syncLoop, "SableSyncThread");
            syncThread.setDaemon(true);
            syncThread.start();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (syncThread != null) syncThread.interrupt();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void syncLoop() {
        // Fetch own user ID once on start
        fetchOwnUserId();

        while (running) {
            try {
                if (!tokenStore.hasSession()) {
                    Thread.sleep(5000);
                    continue;
                }

                String token      = tokenStore.getAccessToken();
                String homeserver = tokenStore.getHomeserver();
                String since      = tokenStore.getSince();

                String syncUrl = homeserver + "/_matrix/client/v3/sync"
                    + "?timeout=" + LONG_POLL_MS
                    + "&filter=" + URLEncoder.encode(
                        "{\"room\":{\"timeline\":{\"limit\":10}},\"presence\":{\"not_types\":[\"*\"]}}",
                        "UTF-8");
                if (since != null) {
                    syncUrl += "&since=" + URLEncoder.encode(since, "UTF-8");
                }

                URL url = new URL(syncUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(LONG_POLL_MS + 10000);

                int code = conn.getResponseCode();

                if (code == 401) {
                    Log.w(TAG, "Token expired, stopping sync");
                    tokenStore.saveSession(null, homeserver);
                    showReloginNotification();
                    Thread.sleep(10000);
                    continue;
                }

                if (code != 200) {
                    Log.w(TAG, "Sync returned " + code + ", retrying in 5s");
                    conn.disconnect();
                    Thread.sleep(5000);
                    continue;
                }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                conn.disconnect();

                JSONObject response = new JSONObject(sb.toString());
                String nextBatch = response.optString("next_batch", null);
                if (nextBatch != null) {
                    boolean firstSync = (since == null);
                    tokenStore.saveSince(nextBatch);
                    if (!firstSync) {
                        processNotifications(response);
                    }
                }

            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "Sync error: " + e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
            }
        }
        Log.i(TAG, "Sync loop ended");
    }

    private void fetchOwnUserId() {
        if (!tokenStore.getOwnUserId().isEmpty()) return;
        try {
            URL url = new URL(tokenStore.getHomeserver() + "/_matrix/client/v3/account/whoami");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + tokenStore.getAccessToken());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                conn.disconnect();
                JSONObject obj = new JSONObject(sb.toString());
                String userId = obj.optString("user_id", "");
                if (!userId.isEmpty()) tokenStore.saveOwnUserId(userId);
            }
        } catch (Exception e) {
            Log.w(TAG, "whoami failed: " + e.getMessage());
        }
    }

    private void processNotifications(JSONObject response) throws Exception {
        JSONObject rooms = response.optJSONObject("rooms");
        if (rooms == null) return;

        JSONObject join = rooms.optJSONObject("join");
        if (join == null) return;

        JSONArray roomIds = join.names();
        if (roomIds == null) return;

        String ownUserId = tokenStore.getOwnUserId();

        for (int i = 0; i < roomIds.length(); i++) {
            String roomId = roomIds.getString(i);
            JSONObject room = join.getJSONObject(roomId);

            // Check push rule evaluation results from Synapse.
            // notification_count > 0 means at least one event passed the user's push rules.
            // highlight_count > 0 means a mention/keyword rule fired.
            // If both are 0, the user has muted this room or has no matching rules — skip it.
            JSONObject unreadNotifs = room.optJSONObject("unread_notifications");
            int notificationCount = unreadNotifs != null ? unreadNotifs.optInt("notification_count", 0) : 0;
            int highlightCount = unreadNotifs != null ? unreadNotifs.optInt("highlight_count", 0) : 0;
            if (notificationCount == 0) continue;

            JSONObject timeline = room.optJSONObject("timeline");
            if (timeline == null) continue;
            JSONArray events = timeline.optJSONArray("events");
            if (events == null) continue;

            // Cache room name from sync state if present
            String roomName = getRoomNameFromState(room);
            if (roomName == null) {
                roomName = profileCache.getRoomName(roomId);
                profileCache.cacheRoomName(roomId, roomName);
            } else {
                profileCache.cacheRoomName(roomId, roomName);
            }

            // Notify on every new message event, skip own messages
            for (int j = events.length() - 1; j >= 0; j--) {
                JSONObject event = events.getJSONObject(j);
                String evType = event.optString("type");
                boolean isMessage   = "m.room.message".equals(evType);
                boolean isEncrypted = "m.room.encrypted".equals(evType);
                if (!isMessage && !isEncrypted) continue;

                String senderFull = event.optString("sender", "");

                // Skip own messages — re-fetch whoami if we don't have it yet
                if (ownUserId.isEmpty()) {
                    fetchOwnUserId();
                    ownUserId = tokenStore.getOwnUserId();
                }
                if (!ownUserId.isEmpty() && senderFull.equals(ownUserId)) continue;

                // Skip if app is open and user is already in this room
                if (MainActivity.isInForeground &&
                    roomId.equals(MainActivity.currentRoomId)) continue;

                String body = null;
                if (!isEncrypted) {
                    JSONObject content = event.optJSONObject("content");
                    if (content == null) continue;
                    String msgtype = content.optString("msgtype", "");
                    if (msgtype.isEmpty()) continue;
                    body = content.optString("body", null);
                }

                // Fetch display name, sender avatar, and room avatar
                String displayName = profileCache.getDisplayName(senderFull);
                android.graphics.Bitmap senderAvatar = profileCache.getAvatar(senderFull);
                android.graphics.Bitmap roomAvatar = profileCache.getRoomAvatar(roomId);

                // It's a DM if 2 or fewer members (joined + invited)
                int joinedCount = room.optInt("joined_member_count", 0);
                int invitedCount = room.optInt("invited_member_count", 0);
                boolean isDm = (joinedCount + invitedCount) <= 2;

                String displayRoomName = (highlightCount > 0) ? "💬 " + roomName : roomName;
                String evId = event.optString("event_id", null);
                notifHelper.showMessage(roomId, displayRoomName, displayName, senderAvatar, roomAvatar,
                    body != null ? body : "New message", isEncrypted, isDm, evId);
                break; // One notif per room per sync
            }
        }
    }

    private String getRoomNameFromState(JSONObject room) {
        try {
            JSONObject state = room.optJSONObject("state");
            if (state != null) {
                JSONArray events = state.optJSONArray("events");
                if (events != null) {
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject e = events.getJSONObject(i);
                        if ("m.room.name".equals(e.optString("type"))) {
                            JSONObject content = e.optJSONObject("content");
                            if (content != null) {
                                String name = content.optString("name", null);
                                if (name != null && !name.isEmpty()) return name;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void showReloginNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_MISC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rewr.chat — Session expired")
            .setContentText("Tap to sign in again")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH);

        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(9001, builder.build());
        } catch (SecurityException ignored) {}
    }

    private void createServiceChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Background Sync", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Keeps Sable connected for instant notifications");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildServiceNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID) // CHANNEL_ID = sable_sync_service_v2 (IMPORTANCE_MIN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rewr.chat")
            .setContentText("Connected")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setGroup(NotificationHelper.GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build();
    }
}
