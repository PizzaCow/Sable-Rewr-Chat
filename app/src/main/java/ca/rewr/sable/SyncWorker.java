package ca.rewr.sable;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class SyncWorker extends Worker {

    private static final String TAG = "SableSync";
    // Short timeout so we don't block the WorkManager thread
    private static final int TIMEOUT_MS = 10000;

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        TokenStore store = new TokenStore(getApplicationContext());
        if (!store.hasSession()) return Result.success();

        String token      = store.getAccessToken();
        String homeserver = store.getHomeserver();
        String since      = store.getSince();

        try {
            String syncUrl = homeserver + "/_matrix/client/v3/sync"
                + "?timeout=" + TIMEOUT_MS
                + "&filter=" + URLEncoder.encode(
                    "{\"room\":{\"timeline\":{\"limit\":5}},\"presence\":{\"not_types\":[\"*\"]}}",
                    "UTF-8");
            if (since != null) {
                syncUrl += "&since=" + URLEncoder.encode(since, "UTF-8");
            }

            URL url = new URL(syncUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(TIMEOUT_MS + 5000);

            int code = conn.getResponseCode();
            if (code == 401) {
                // Token expired — clear it so we stop trying
                store.saveSession(null, homeserver);
                return Result.success();
            }
            if (code != 200) return Result.retry();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            conn.disconnect();

            JSONObject response = new JSONObject(sb.toString());
            String nextBatch = response.optString("next_batch", null);
            if (nextBatch != null) store.saveSince(nextBatch);

            // Only notify if we have a since token (not on first sync)
            if (since != null) {
                processNotifications(response);
            }

        } catch (Exception e) {
            Log.e(TAG, "Sync failed", e);
            return Result.retry();
        }

        return Result.success();
    }

    private void processNotifications(JSONObject response) throws Exception {
        NotificationHelper helper = new NotificationHelper(getApplicationContext());

        JSONObject rooms = response.optJSONObject("rooms");
        if (rooms == null) return;

        JSONObject join = rooms.optJSONObject("join");
        if (join == null) return;

        JSONArray roomIds = join.names();
        if (roomIds == null) return;

        for (int i = 0; i < roomIds.length(); i++) {
            String roomId = roomIds.getString(i);
            JSONObject room = join.getJSONObject(roomId);

            // Get room name from state
            String roomName = getRoomName(room, roomId);

            JSONObject timeline = room.optJSONObject("timeline");
            if (timeline == null) continue;

            JSONArray events = timeline.optJSONArray("events");
            if (events == null) continue;

            for (int j = 0; j < events.length(); j++) {
                JSONObject event = events.getJSONObject(j);
                if (!"m.room.message".equals(event.optString("type"))) continue;

                // Check if this event has a notification (unread_notifications count > 0)
                JSONObject notifCounts = room.optJSONObject("unread_notifications");
                int highlightCount = notifCounts != null ? notifCounts.optInt("highlight_count", 0) : 0;
                int notifCount = notifCounts != null ? notifCounts.optInt("notification_count", 0) : 0;
                if (notifCount == 0) continue;

                JSONObject content = event.optJSONObject("content");
                if (content == null) continue;

                String sender = event.optString("sender", "Someone");
                // Strip @user:server.com -> user
                if (sender.startsWith("@")) sender = sender.substring(1, sender.indexOf(':') > 0 ? sender.indexOf(':') : sender.length());

                String body = content.optString("body", "New message");
                String title = highlightCount > 0 ? "💬 " + roomName : roomName;

                helper.showMessage(roomId, roomName, sender, null, null, body, false, false);
                break; // One notification per room
            }
        }
    }

    private String getRoomName(JSONObject room, String fallback) {
        try {
            JSONObject state = room.optJSONObject("state");
            if (state != null) {
                JSONArray events = state.optJSONArray("events");
                if (events != null) {
                    for (int i = 0; i < events.length(); i++) {
                        JSONObject e = events.getJSONObject(i);
                        if ("m.room.name".equals(e.optString("type"))) {
                            String name = e.optJSONObject("content") != null
                                ? e.getJSONObject("content").optString("name", null)
                                : null;
                            if (name != null && !name.isEmpty()) return name;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }
}
