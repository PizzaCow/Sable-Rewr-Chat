package ca.rewr.sable;

import android.content.Context;
import android.content.SharedPreferences;

public class TokenStore {

    private static final String PREFS = "sable_session";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_HOMESERVER = "homeserver";
    private static final String KEY_SINCE = "sync_since";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_NTFY_TOPIC = "ntfy_topic";
    private static final String KEY_PUSHER_REGISTERED = "pusher_registered";

    private final SharedPreferences prefs;

    public TokenStore(Context context) {
        prefs = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void saveSession(String accessToken, String homeserver) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_HOMESERVER, homeserver)
            // New session means the old pusher registration is no longer valid
            .putBoolean(KEY_PUSHER_REGISTERED, false)
            .apply();
    }

    public String getAccessToken() { return prefs.getString(KEY_ACCESS_TOKEN, null); }
    public String getHomeserver()  { return prefs.getString(KEY_HOMESERVER, "https://matrix.rewr.ca"); }

    public String getSince()            { return prefs.getString(KEY_SINCE, null); }
    public void saveSince(String since) { prefs.edit().putString(KEY_SINCE, since).apply(); }

    public String getOwnUserId()              { return prefs.getString(KEY_USER_ID, ""); }
    public void saveOwnUserId(String userId)  { prefs.edit().putString(KEY_USER_ID, userId).apply(); }

    public boolean hasSession() { return getAccessToken() != null; }

    /** Returns persisted ntfy topic UUID, or null if not yet generated. */
    public String getNtfyTopic() { return prefs.getString(KEY_NTFY_TOPIC, null); }

    /** Persist the ntfy topic UUID (generated once, reused forever). */
    public void saveNtfyTopic(String topic) {
        prefs.edit().putString(KEY_NTFY_TOPIC, topic).apply();
    }

    /** Whether we have successfully registered the pusher with Synapse for the current session. */
    public boolean isPusherRegistered() { return prefs.getBoolean(KEY_PUSHER_REGISTERED, false); }
    public void setPusherRegistered(boolean registered) {
        prefs.edit().putBoolean(KEY_PUSHER_REGISTERED, registered).apply();
    }
}
