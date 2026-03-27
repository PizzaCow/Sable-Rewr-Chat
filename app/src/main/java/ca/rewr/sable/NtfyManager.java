package ca.rewr.sable;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Manages the device's ntfy push topic and Synapse pusher registration.
 *
 * Flow:
 *  1. ensureRegistered() is called on login (WebNotificationInterface) and service start.
 *  2. A stable UUID topic is generated once and persisted in TokenStore.
 *  3. A pusher is registered with Synapse pointing at the ntfy gateway.
 *  4. SyncService opens an HTTP streaming connection to the ntfy topic and dispatches
 *     incoming Matrix push payloads as local notifications.
 */
public class NtfyManager {

    private static final String TAG = "SableNtfy";

    public static final String NTFY_BASE      = "https://push.rewr.ca";
    public static final String NTFY_AUTH      = "Bearer NtfyMatrixPush2026!";
    public static final String PUSH_GATEWAY   = NTFY_BASE + "/_matrix/push/v1/notify";

    private static final String APP_ID             = "ca.rewr.sable";
    private static final String APP_DISPLAY_NAME   = "Rewr.chat";
    private static final String DEVICE_DISPLAY_NAME = "Android";

    /**
     * Ensures a stable ntfy topic exists and the Synapse pusher is registered.
     * Safe to call multiple times — idempotent (checks isPusherRegistered flag).
     * Runs network work on a background thread.
     */
    public static void ensureRegistered(Context context) {
        TokenStore ts = new TokenStore(context);
        if (!ts.hasSession()) return;

        // Generate topic UUID once
        String topic = ts.getNtfyTopic();
        if (topic == null || topic.isEmpty()) {
            topic = UUID.randomUUID().toString();
            ts.saveNtfyTopic(topic);
            Log.i(TAG, "Generated new ntfy topic: " + topic);
        }

        if (ts.isPusherRegistered()) {
            Log.d(TAG, "Pusher already registered, skipping");
            return;
        }

        final String finalTopic = topic;
        final String accessToken = ts.getAccessToken();
        final String homeserver  = ts.getHomeserver();

        new Thread(() -> registerPusher(context, finalTopic, accessToken, homeserver),
            "SableNtfyRegister").start();
    }

    private static void registerPusher(Context context, String topic,
                                        String accessToken, String homeserver) {
        try {
            String pushKey = NTFY_BASE + "/" + topic;

            JSONObject data = new JSONObject();
            // Full payload format — no "format":"event_id_only" so Synapse sends
            // content.body, sender_display_name, room_name, etc. in the push.
            data.put("url", PUSH_GATEWAY);

            JSONObject body = new JSONObject();
            body.put("kind", "http");
            body.put("app_id", APP_ID);
            body.put("app_display_name", APP_DISPLAY_NAME);
            body.put("device_display_name", DEVICE_DISPLAY_NAME);
            body.put("pushkey", pushKey);
            body.put("lang", "en");
            body.put("append", false);
            body.put("data", data);

            URL url = new URL(homeserver + "/_matrix/client/v3/pushers/set");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
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

            if (code == 200) {
                Log.i(TAG, "Pusher registered successfully (pushkey=" + pushKey + ")");
                new TokenStore(context).setPusherRegistered(true);
            } else {
                Log.w(TAG, "Pusher registration returned HTTP " + code + " — will retry next session");
            }
        } catch (Exception e) {
            Log.e(TAG, "Pusher registration failed: " + e.getMessage());
        }
    }

    /** Returns the streaming URL for this device's ntfy topic. */
    public static String streamUrl(TokenStore ts) {
        String topic = ts.getNtfyTopic();
        if (topic == null || topic.isEmpty()) return null;
        return NTFY_BASE + "/" + topic + "/json";
    }
}
