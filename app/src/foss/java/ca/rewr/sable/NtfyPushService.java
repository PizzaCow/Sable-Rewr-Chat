package ca.rewr.sable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Foreground service that maintains an SSE connection to ntfy.rewr.ca and
 * performs a /sync call whenever a push arrives. This replaces the old
 * UnifiedPush approach — no external distributor app required.
 */
public class NtfyPushService extends Service {

    private static final String TAG = "NtfyPushService";
    private static final String NTFY_BASE = "https://ntfy.rewr.ca/";
    static final String CHANNEL_ID = "sable_ntfy_v1";
    private static final int FOREGROUND_NOTIF_ID = 9001;

    private TokenStore tokenStore;
    private NotificationHelper notifHelper;
    private MatrixProfileCache profileCache;

    private volatile boolean running = false;
    private Thread sseThread;

    @Override
    public void onCreate() {
        super.onCreate();
        tokenStore = new TokenStore(this);
        notifHelper = new NotificationHelper(this);
        profileCache = new MatrixProfileCache(this);
        createForegroundChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification());

        if (sseThread == null || !sseThread.isAlive()) {
            running = true;
            sseThread = new Thread(this::sseLoop, "ntfy-sse");
            sseThread.setDaemon(true);
            sseThread.start();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (sseThread != null) sseThread.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Foreground notification channel ───────────────────────────────────────

    private void createForegroundChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Push Connection", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("Keeps push notifications connected");
        ch.setSound(null, null);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildForegroundNotification() {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rewr.chat")
            .setContentText("Connected to push notifications")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    // ── SSE loop ───────────────────────────────────────────────────────────────

    private void sseLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            String topic = tokenStore.getNtfyTopic();
            if (topic == null || topic.isEmpty()) {
                sleepSeconds(10);
                continue;
            }
            if (!tokenStore.hasSession()) {
                sleepSeconds(10);
                continue;
            }

            HttpURLConnection conn = null;
            try {
                URL url = new URL(NTFY_BASE + topic + "/sse");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(0); // keep-alive forever
                conn.setRequestProperty("Accept", "text/event-stream");

                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "SSE non-200: " + code);
                    conn.disconnect();
                    sleepSeconds(60);
                    continue;
                }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));

                String eventType = null;
                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        // data line — ignore content, we just use it as a wakeup
                    } else if (line.isEmpty()) {
                        // blank line = end of event
                        if ("message".equals(eventType)) {
                            syncOnce();
                        }
                        eventType = null;
                    }
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) break;
                Log.w(TAG, "SSE error: " + e.getMessage());
                if (conn != null) conn.disconnect();
                sleepSeconds(5);
            }
        }
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    private void syncOnce() {
        try {
            String accessToken = tokenStore.getAccessToken();
            String homeserver  = tokenStore.getHomeserver();
            String since       = tokenStore.getSince();

            if (accessToken == null || homeserver == null) return;

            String ownUserId = fetchOwnUserId(homeserver, accessToken);

            StringBuilder urlBuilder = new StringBuilder(homeserver)
                .append("/_matrix/client/v3/sync?timeout=0&filter=%7B%22room%22%3A%7B%22timeline%22%3A%7B%22limit%22%3A5%7D%7D%7D");
            if (since != null) urlBuilder.append("&since=").append(since);

            URL url = new URL(urlBuilder.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);

            int code = conn.getResponseCode();
            if (code == 401) {
                conn.disconnect();
                showReloginNotification();
                return;
            }
            if (code != 200) {
                conn.disconnect();
                return;
            }

            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            conn.disconnect();

            JSONObject sync = new JSONObject(sb.toString());
            String nextBatch = sync.optString("next_batch", null);

            if (since == null) {
                // First sync — just save the token, don't show stale notifications
                if (nextBatch != null) tokenStore.saveSince(nextBatch);
                return;
            }

            // Process joined rooms
            JSONObject rooms = sync.optJSONObject("rooms");
            if (rooms != null) {
                JSONObject join = rooms.optJSONObject("join");
                if (join != null) {
                    java.util.Iterator<String> keys = join.keys();
                    while (keys.hasNext()) {
                        String roomId = keys.next();
                        JSONObject room = join.getJSONObject(roomId);
                        processRoom(roomId, room, ownUserId);
                    }
                }
            }

            if (nextBatch != null) tokenStore.saveSince(nextBatch);

        } catch (Exception e) {
            Log.e(TAG, "syncOnce error: " + e.getMessage());
        }
    }

    private void processRoom(String roomId, JSONObject room, String ownUserId) {
        try {
            // Check notification count
            JSONObject unread = room.optJSONObject("unread_notifications");
            int notifCount = unread != null ? unread.optInt("notification_count", 0) : 0;
            int highlightCount = unread != null ? unread.optInt("highlight_count", 0) : 0;
            if (notifCount <= 0) return;

            // Skip if app is in foreground viewing this room
            if (MainActivity.isInForeground && roomId.equals(MainActivity.currentRoomId)) return;

            // Get timeline events
            JSONObject timeline = room.optJSONObject("timeline");
            JSONArray events = timeline != null ? timeline.optJSONArray("events") : null;
            if (events == null || events.length() == 0) return;

            // Find the last message event (m.room.message), skip own messages
            JSONObject lastMsgEvent = null;
            String lastSenderId = null;
            for (int i = events.length() - 1; i >= 0; i--) {
                JSONObject ev = events.getJSONObject(i);
                if (!"m.room.message".equals(ev.optString("type"))) continue;
                String sender = ev.optString("sender");
                if (sender.equals(ownUserId)) continue;
                lastMsgEvent = ev;
                lastSenderId = sender;
                break;
            }
            if (lastMsgEvent == null) return;

            // Determine room name from state events
            JSONObject state = room.optJSONObject("state");
            JSONArray stateEvents = state != null ? state.optJSONArray("events") : null;
            String roomNameFromState = getRoomNameFromState(stateEvents);
            if (roomNameFromState != null) profileCache.cacheRoomName(roomId, roomNameFromState);

            String roomName = profileCache.getRoomName(roomId);
            if (highlightCount > 0) roomName = "💬 " + roomName;

            // DM detection via summary
            JSONObject summary = room.optJSONObject("summary");
            boolean isDm;
            if (summary != null && summary.has("m.joined_member_count")) {
                isDm = summary.optInt("m.joined_member_count", 3) <= 2;
                profileCache.cacheDm(roomId, isDm);
            } else {
                isDm = profileCache.isDm(roomId);
            }

            // Fetch sender info
            String senderName = profileCache.getDisplayName(lastSenderId);
            Bitmap senderAvatar = profileCache.getAvatar(lastSenderId);
            Bitmap roomAvatar = isDm ? null : profileCache.getRoomAvatar(roomId);

            // Message body
            JSONObject content = lastMsgEvent.optJSONObject("content");
            String body = content != null ? content.optString("body", "New message") : "New message";
            boolean isEncrypted = "m.room.encrypted".equals(lastMsgEvent.optString("type"));

            String eventId = lastMsgEvent.optString("event_id", null);

            notifHelper.showMessage(roomId, roomName, senderName, senderAvatar,
                roomAvatar, body, isEncrypted, isDm, eventId);

        } catch (Exception e) {
            Log.e(TAG, "processRoom error for " + roomId + ": " + e.getMessage());
        }
    }

    private String getRoomNameFromState(JSONArray stateEvents) {
        if (stateEvents == null) return null;
        String aliasName = null;
        for (int i = 0; i < stateEvents.length(); i++) {
            try {
                JSONObject ev = stateEvents.getJSONObject(i);
                String type = ev.optString("type");
                JSONObject content = ev.optJSONObject("content");
                if (content == null) continue;
                if ("m.room.name".equals(type)) {
                    String name = content.optString("name", null);
                    if (name != null && !name.isEmpty()) return name;
                } else if ("m.room.canonical_alias".equals(type) && aliasName == null) {
                    String alias = content.optString("alias", null);
                    if (alias != null && alias.startsWith("#")) {
                        int colon = alias.indexOf(':');
                        aliasName = colon > 0 ? alias.substring(1, colon) : alias.substring(1);
                    }
                }
            } catch (Exception ignored) {}
        }
        return aliasName;
    }

    private String fetchOwnUserId(String homeserver, String accessToken) {
        String cached = tokenStore.getOwnUserId();
        if (cached != null && !cached.isEmpty()) return cached;
        try {
            URL url = new URL(homeserver + "/_matrix/client/v3/account/whoami");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(8_000);
            if (conn.getResponseCode() == 200) {
                BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                conn.disconnect();
                JSONObject obj = new JSONObject(sb.toString());
                String userId = obj.optString("user_id", null);
                if (userId != null) {
                    tokenStore.saveOwnUserId(userId);
                    return userId;
                }
            } else {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchOwnUserId error: " + e.getMessage());
        }
        return "";
    }

    private void showReloginNotification() {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID_MISC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Session expired")
            .setContentText("Tap to sign in again")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build();

        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(9002, n);
        } catch (Exception ignored) {}

        // Pause sync — stop the service; will restart on next login
        running = false;
    }

    private void sleepSeconds(int seconds) {
        try { Thread.sleep(seconds * 1000L); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
