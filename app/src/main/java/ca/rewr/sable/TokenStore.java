package ca.rewr.sable;

import android.content.Context;
import android.content.SharedPreferences;

public class TokenStore {

    private static final String PREFS = "sable_session";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_HOMESERVER = "homeserver";
    private static final String KEY_SINCE = "sync_since";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_UP_ENDPOINT = "up_endpoint";

    private final SharedPreferences prefs;

    public TokenStore(Context context) {
        prefs = context.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void saveSession(String accessToken, String homeserver) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_HOMESERVER, homeserver)
            .apply();
    }

    public String getAccessToken() { return prefs.getString(KEY_ACCESS_TOKEN, null); }
    public String getHomeserver()  { return prefs.getString(KEY_HOMESERVER, "https://matrix.rewr.ca"); }

    public String getSince()        { return prefs.getString(KEY_SINCE, null); }
    public void saveSince(String since) { prefs.edit().putString(KEY_SINCE, since).apply(); }

    public String getOwnUserId()    { return prefs.getString(KEY_USER_ID, ""); }
    public void saveOwnUserId(String userId) { prefs.edit().putString(KEY_USER_ID, userId).apply(); }

    public boolean hasSession()     { return getAccessToken() != null; }

    public String getUpEndpoint()   { return prefs.getString(KEY_UP_ENDPOINT, null); }
    public void saveUpEndpoint(String endpoint) {
        if (endpoint == null) {
            prefs.edit().remove(KEY_UP_ENDPOINT).apply();
        } else {
            prefs.edit().putString(KEY_UP_ENDPOINT, endpoint).apply();
        }
    }
}
