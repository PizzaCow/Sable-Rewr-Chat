package ca.rewr.sable;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

public class TokenStore {

    private static final String TAG = "TokenStore";
    private static final String PREFS = "sable_session_encrypted";
    private static final String LEGACY_PREFS = "sable_session";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_HOMESERVER = "homeserver";
    private static final String KEY_SINCE = "sync_since";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_FCM_TOKEN = "fcm_token";
    private static final String KEY_FCM_PUSHER_REGISTERED = "fcm_pusher_registered";

    private final SharedPreferences prefs;

    public TokenStore(Context context) {
        SharedPreferences encrypted = null;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            encrypted = EncryptedSharedPreferences.create(
                PREFS,
                masterKeyAlias,
                context.getApplicationContext(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to plain", e);
            encrypted = context.getApplicationContext()
                .getSharedPreferences(PREFS + "_fallback", Context.MODE_PRIVATE);
        }
        this.prefs = encrypted;

        // Migrate from legacy unencrypted prefs (one-time)
        migrateFromLegacy(context);
    }

    /**
     * One-time migration: copies data from the old unencrypted SharedPreferences
     * into encrypted storage, then clears the old prefs.
     */
    private void migrateFromLegacy(Context context) {
        SharedPreferences legacy = context.getApplicationContext()
            .getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        String legacyToken = legacy.getString(KEY_ACCESS_TOKEN, null);
        if (legacyToken != null) {
            Log.i(TAG, "Migrating session from unencrypted to encrypted storage");
            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, legacyToken)
                .putString(KEY_HOMESERVER, legacy.getString(KEY_HOMESERVER, Config.DEFAULT_HOMESERVER))
                .putString(KEY_SINCE, legacy.getString(KEY_SINCE, null))
                .putString(KEY_USER_ID, legacy.getString(KEY_USER_ID, ""))
                .putString(KEY_FCM_TOKEN, legacy.getString(KEY_FCM_TOKEN, null))
                .putBoolean(KEY_FCM_PUSHER_REGISTERED, legacy.getBoolean(KEY_FCM_PUSHER_REGISTERED, false))
                .apply();
            // Clear legacy data
            legacy.edit().clear().apply();
        }
    }

    public void saveSession(String accessToken, String homeserver) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_HOMESERVER, homeserver)
            // New session means the old pusher registration is no longer valid
            .putBoolean(KEY_FCM_PUSHER_REGISTERED, false)
            .apply();
    }

    public String getAccessToken() { return prefs.getString(KEY_ACCESS_TOKEN, null); }
    public String getHomeserver()  { return prefs.getString(KEY_HOMESERVER, Config.DEFAULT_HOMESERVER); }

    public String getSince()            { return prefs.getString(KEY_SINCE, null); }
    public void saveSince(String since) { prefs.edit().putString(KEY_SINCE, since).apply(); }

    public String getOwnUserId()              { return prefs.getString(KEY_USER_ID, ""); }
    public void saveOwnUserId(String userId)  { prefs.edit().putString(KEY_USER_ID, userId).apply(); }

    public boolean hasSession() { return getAccessToken() != null; }

    /** Returns persisted FCM token, or null if not yet available. */
    public String getFcmToken() { return prefs.getString(KEY_FCM_TOKEN, null); }

    /** Persist the FCM device token. */
    public void saveFcmToken(String token) {
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply();
    }

    /** Whether we have successfully registered the FCM pusher with Synapse for the current session. */
    public boolean isFcmPusherRegistered() { return prefs.getBoolean(KEY_FCM_PUSHER_REGISTERED, false); }
    public void setFcmPusherRegistered(boolean registered) {
        prefs.edit().putBoolean(KEY_FCM_PUSHER_REGISTERED, registered).apply();
    }
}
