package ca.rewr.sable;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * FOSS flavor push provider — uses built-in ntfy SSE streaming.
 * No Firebase, no Google Play Services, no external distributor app required.
 */
public class PushProviderImpl implements PushProvider {

    private static final String TAG = "PushProviderFoss";
    private static final String NTFY_BASE = "https://ntfy.rewr.ca/";

    @Override
    public void init(Context context, TokenStore tokenStore) {
        // 1. Ensure ntfy topic UUID exists
        tokenStore.generateAndSaveNtfyTopic();

        // 2. Register pusher with Synapse if needed
        if (tokenStore.hasSession() && !tokenStore.isNtfyPusherRegistered()) {
            registerNtfyPusher(context, tokenStore);
        }

        // 3. Start the SSE foreground service
        Intent serviceIntent = new Intent(context, NtfyPushService.class);
        ContextCompat.startForegroundService(context, serviceIntent);
    }

    @Override
    public void onSessionChanged(Context context, TokenStore tokenStore) {
        // New session — pusher registration is no longer valid, re-register
        tokenStore.setNtfyPusherRegistered(false);
        // Also clear cached sync token so we do a fresh initial sync
        tokenStore.saveSince(null);
        registerNtfyPusher(context, tokenStore);
        // Ensure service is running (may not have been started if init() ran before login)
        Intent serviceIntent = new Intent(context, NtfyPushService.class);
        ContextCompat.startForegroundService(context, serviceIntent);
    }

    /**
     * Registers an HTTP pusher pointing at ntfy.rewr.ca/{topic} with Synapse.
     * Runs in a background thread.
     */
    public static void registerNtfyPusher(Context context, TokenStore tokenStore) {
        new Thread(() -> {
            try {
                String topic      = tokenStore.getNtfyTopic();
                String homeserver = tokenStore.getHomeserver();
                String token      = tokenStore.getAccessToken();

                if (topic == null || homeserver == null || token == null) return;

                String pushKey = NTFY_BASE + topic;
                String pushUrl = pushKey; // ntfy.rewr.ca acts as the push gateway

                JSONObject data = new JSONObject();
                data.put("url", pushUrl);
                data.put("format", "event_id_only");

                JSONObject body = new JSONObject();
                body.put("kind", "http");
                body.put("app_id", "ca.rewr.sable.ntfy");
                body.put("app_display_name", "Rewr.chat");
                body.put("device_display_name", "Android");
                body.put("pushkey", pushKey);
                body.put("lang", "en");
                body.put("append", false);
                body.put("data", data);

                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

                URL url = new URL(homeserver + "/_matrix/client/v3/pushers/set");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                int code = conn.getResponseCode();
                conn.disconnect();

                if (code == 200) {
                    tokenStore.setNtfyPusherRegistered(true);
                    Log.i(TAG, "ntfy pusher registered successfully");
                } else {
                    Log.w(TAG, "ntfy pusher registration failed: HTTP " + code);
                }
            } catch (Exception e) {
                Log.e(TAG, "registerNtfyPusher error: " + e.getMessage());
            }
        }, "ntfy-pusher-reg").start();
    }
}
