package ca.rewr.sable;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class PusherRegistrar {
    private static final String TAG = "SablePusher";
    private static final String APP_ID = "ca.rewr.sable";
    private static final String APP_DISPLAY_NAME = "Rewr.chat";
    private static final String DEVICE_DISPLAY_NAME = "Android";
    private static final String PUSH_GATEWAY_URL = "https://push.rewr.ca/_matrix/push/v1/notify";

    public static void register(Context context, String endpoint, String accessToken, String homeserver) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("kind", "http");
                body.put("app_id", APP_ID);
                body.put("app_display_name", APP_DISPLAY_NAME);
                body.put("device_display_name", DEVICE_DISPLAY_NAME);
                body.put("pushkey", endpoint);
                body.put("lang", "en");
                body.put("append", false);
                JSONObject data = new JSONObject();
                // Use the fixed ntfy gateway URL, not the per-topic endpoint
                data.put("url", PUSH_GATEWAY_URL);
                data.put("format", "event_id_only");
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
                Log.i(TAG, "Pusher register response: " + code);
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to register pusher: " + e.getMessage());
            }
        }).start();
    }

    public static void unregister(Context context, String accessToken, String homeserver) {
        new Thread(() -> {
            try {
                TokenStore ts = new TokenStore(context);
                String endpoint = ts.getUpEndpoint();
                if (endpoint == null || endpoint.isEmpty()) return;

                JSONObject body = new JSONObject();
                body.put("kind", JSONObject.NULL);  // kind=null removes the pusher
                body.put("app_id", APP_ID);
                body.put("pushkey", endpoint);

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
                Log.i(TAG, "Pusher unregister response: " + code);
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister pusher: " + e.getMessage());
            }
        }).start();
    }
}
