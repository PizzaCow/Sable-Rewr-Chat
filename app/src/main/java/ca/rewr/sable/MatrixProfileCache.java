package ca.rewr.sable;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fetches and caches Matrix display names, avatar bitmaps, and room names.
 */
public class MatrixProfileCache {

    private final TokenStore tokenStore;
    private final LruCache<String, String>  displayNames = new LruCache<>(200);
    private final LruCache<String, String>  roomNames    = new LruCache<>(200);
    private final LruCache<String, Bitmap>  avatars      = new LruCache<>(50);
    private final LruCache<String, Boolean> dmCache      = new LruCache<>(200);

    public MatrixProfileCache(Context context) {
        this.tokenStore = new TokenStore(context);
    }

    /** Returns display name for a Matrix user ID, falls back to localpart */
    public String getDisplayName(String userId) {
        String cached = displayNames.get(userId);
        if (cached != null) return cached;

        try {
            String url = tokenStore.getHomeserver()
                + "/_matrix/client/v3/profile/" + encode(userId) + "/displayname";
            JSONObject res = getJson(url);
            if (res != null) {
                String name = res.optString("displayname", null);
                if (name != null && !name.isEmpty()) {
                    displayNames.put(userId, name);
                    return name;
                }
            }
        } catch (Exception ignored) {}

        // Fallback: use localpart
        String localpart = userId.startsWith("@") ? userId.substring(1) : userId;
        int colon = localpart.indexOf(':');
        String fallback = colon > 0 ? localpart.substring(0, colon) : localpart;
        displayNames.put(userId, fallback);
        return fallback;
    }

    /** Returns room name, falls back to DM partner's display name, then room ID */
    public String getRoomName(String roomId) {
        String cached = roomNames.get(roomId);
        if (cached != null) return cached;

        // 1. Try m.room.name
        try {
            String url = tokenStore.getHomeserver()
                + "/_matrix/client/v3/rooms/" + encode(roomId) + "/state/m.room.name";
            JSONObject res = getJson(url);
            if (res != null) {
                String name = res.optString("name", null);
                if (name != null && !name.isEmpty()) {
                    roomNames.put(roomId, name);
                    return name;
                }
            }
        } catch (Exception ignored) {}

        // 2. Try canonical alias
        try {
            String url = tokenStore.getHomeserver()
                + "/_matrix/client/v3/rooms/" + encode(roomId) + "/state/m.room.canonical_alias";
            JSONObject res = getJson(url);
            if (res != null) {
                String alias = res.optString("alias", null);
                if (alias != null && alias.startsWith("#")) {
                    int colon = alias.indexOf(':');
                    String name = colon > 0 ? alias.substring(1, colon) : alias.substring(1);
                    roomNames.put(roomId, name);
                    return name;
                }
            }
        } catch (Exception ignored) {}

        // 3. Try full room state API (handles federated servers where individual state lookups may 404)
        String stateApiName = getRoomNameFromStateApi(roomId);
        if (stateApiName != null) {
            roomNames.put(roomId, stateApiName);
            return stateApiName;
        }

        // 4. DM fallback: get members and use the other person's display name
        try {
            String ownUserId = tokenStore.getOwnUserId();
            String url = tokenStore.getHomeserver()
                + "/_matrix/client/v3/rooms/" + encode(roomId) + "/members?membership=join";
            JSONObject res = getJson(url);
            if (res != null) {
                org.json.JSONArray chunk = res.optJSONArray("chunk");
                if (chunk != null) {
                    for (int i = 0; i < chunk.length(); i++) {
                        JSONObject member = chunk.getJSONObject(i);
                        String userId = member.optString("state_key", "");
                        if (!userId.equals(ownUserId) && !userId.isEmpty()) {
                            // Use their display name from content, or fetch it
                            JSONObject content = member.optJSONObject("content");
                            String name = content != null ? content.optString("displayname", null) : null;
                            if (name == null || name.isEmpty()) name = getDisplayName(userId);
                            roomNames.put(roomId, name);
                            return name;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 5. Last resort: shorten room ID (remove the random part)
        String fallback = roomId.startsWith("!") ? roomId.substring(1) : roomId;
        int colon = fallback.indexOf(':');
        if (colon > 0) {
            // Use the server domain as the name rather than the random ID
            fallback = fallback.substring(colon + 1);
        } else {
            fallback = fallback.substring(0, Math.min(fallback.length(), 12));
        }
        roomNames.put(roomId, fallback);
        return fallback;
    }

    /** Cache a room name from sync state events */
    public void cacheRoomName(String roomId, String name) {
        if (name != null && !name.isEmpty()) roomNames.put(roomId, name);
    }

    /** Cache whether a room is a DM (from sync summary or caller-computed). */
    public void cacheDm(String roomId, boolean isDm) {
        dmCache.put(roomId, isDm);
    }

    /**
     * Returns whether a room is a DM. Checks cache first, then fetches member count
     * from Synapse (/joined_members). Falls back to false (treat as group) on error.
     */
    public boolean isDm(String roomId) {
        Boolean cached = dmCache.get(roomId);
        if (cached != null) return cached;

        try {
            String url = tokenStore.getHomeserver()
                + "/_matrix/client/v3/rooms/" + encode(roomId) + "/joined_members";
            JSONObject res = getJson(url);
            if (res != null) {
                JSONObject joined = res.optJSONObject("joined");
                boolean dm = (joined != null && joined.length() <= 2);
                dmCache.put(roomId, dm);
                return dm;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Returns room avatar Bitmap, or null if none set (e.g. DMs) */
    public Bitmap getRoomAvatar(String roomId) {
        Bitmap cached = avatars.get("room:" + roomId);
        if (cached != null) return cached;

        try {
            String url = tokenStore.getHomeserver()
                + "/_matrix/client/v3/rooms/" + encode(roomId) + "/state/m.room.avatar";
            JSONObject res = getJson(url);
            if (res == null) return null;

            String mxcUrl = res.optString("url", null);
            if (mxcUrl == null || !mxcUrl.startsWith("mxc://")) return null;

            String mxc = mxcUrl.substring(6);
            int slash = mxc.indexOf('/');
            if (slash < 0) return null;
            String server = mxc.substring(0, slash);
            String mediaId = mxc.substring(slash + 1);
            String thumbUrl = tokenStore.getHomeserver()
                + "/_matrix/client/v1/media/thumbnail/" + server + "/" + mediaId
                + "?width=96&height=96&method=crop";

            Bitmap bmp = downloadBitmap(thumbUrl);
            if (bmp == null) {
                String legacyUrl = tokenStore.getHomeserver()
                    + "/_matrix/media/v3/thumbnail/" + server + "/" + mediaId
                    + "?width=96&height=96&method=crop";
                bmp = downloadBitmap(legacyUrl);
            }
            if (bmp != null) {
                avatars.put("room:" + roomId, bmp);
                return bmp;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Returns avatar Bitmap for a user ID, or null if unavailable */
    public Bitmap getAvatar(String userId) {
        Bitmap cached = avatars.get(userId);
        if (cached != null) return cached;

        try {
            String url = tokenStore.getHomeserver()
                + "/_matrix/client/v3/profile/" + encode(userId) + "/avatar_url";
            JSONObject res = getJson(url);
            if (res == null) { return null; }

            String mxcUrl = res.optString("avatar_url", null);
            if (mxcUrl == null || !mxcUrl.startsWith("mxc://")) { return null; }

            String mxc = mxcUrl.substring(6);
            int slash = mxc.indexOf('/');
            if (slash < 0) return null;
            String server = mxc.substring(0, slash);
            String mediaId = mxc.substring(slash + 1);
            // Try authenticated endpoint first, fall back to legacy unauthed
            String thumbUrl = tokenStore.getHomeserver()
                + "/_matrix/client/v1/media/thumbnail/" + server + "/" + mediaId
                + "?width=96&height=96&method=crop";

            Bitmap bmp = downloadBitmap(thumbUrl);
            if (bmp == null) {
                // Fallback: legacy media API (no auth needed on most servers)
                String legacyUrl = tokenStore.getHomeserver()
                    + "/_matrix/media/v3/thumbnail/" + server + "/" + mediaId
                    + "?width=96&height=96&method=crop";
                bmp = downloadBitmap(legacyUrl);
            }
            if (bmp != null) {
                avatars.put(userId, bmp);
                return bmp;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Fetches the full room state array and extracts a room name or alias.
     * Returns null if nothing useful is found or on any error.
     */
    private String getRoomNameFromStateApi(String roomId) {
        try {
            String url = tokenStore.getHomeserver()
                + "/_matrix/client/v3/rooms/" + encode(roomId) + "/state";
            URL u = new URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + tokenStore.getAccessToken());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            conn.disconnect();

            JSONArray events = new JSONArray(sb.toString());
            String aliasName = null;
            for (int i = 0; i < events.length(); i++) {
                JSONObject ev = events.getJSONObject(i);
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
            }
            return aliasName; // null if not found
        } catch (Exception ignored) {}
        return null;
    }

    private JSONObject getJson(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + tokenStore.getAccessToken());
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
        BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        conn.disconnect();
        return new JSONObject(sb.toString());
    }

    private Bitmap downloadBitmap(String urlStr) throws Exception {
        // Follow up to 5 redirects manually (HttpURLConnection won't follow http->https)
        String currentUrl = urlStr;
        for (int i = 0; i < 5; i++) {
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Authorization", "Bearer " + tokenStore.getAccessToken());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) break;
                currentUrl = location;
                continue;
            }
            if (code != 200) { conn.disconnect(); return null; }

            InputStream is = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(is);
            conn.disconnect();
            return bmp;
        }
        return null;
    }

    private String encode(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }
}
