package ca.rewr.sable;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

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
    private final LruCache<String, String> displayNames = new LruCache<>(200);
    private final LruCache<String, String> roomNames    = new LruCache<>(200);
    private final LruCache<String, Bitmap> avatars      = new LruCache<>(50);

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

        // 3. DM fallback: get members and use the other person's display name
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

        // 4. Last resort: shorten room ID (remove the random part)
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
            if (res == null) return null;

            String mxcUrl = res.optString("avatar_url", null);
            if (mxcUrl == null || !mxcUrl.startsWith("mxc://")) return null;

            // Convert mxc:// to HTTP thumbnail URL
            String mxc = mxcUrl.substring(6); // strip mxc://
            int slash = mxc.indexOf('/');
            if (slash < 0) return null;
            String server = mxc.substring(0, slash);
            String mediaId = mxc.substring(slash + 1);
            String thumbUrl = tokenStore.getHomeserver()
                + "/_matrix/client/v1/media/thumbnail/" + server + "/" + mediaId
                + "?width=96&height=96&method=crop";

            Bitmap bmp = downloadBitmap(thumbUrl);
            if (bmp != null) {
                // Crop to circle shape is handled by Android's notification system
                avatars.put(userId, bmp);
                return bmp;
            }
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
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + tokenStore.getAccessToken());
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        if (conn.getResponseCode() != 200) { conn.disconnect(); return null; }
        InputStream is = conn.getInputStream();
        Bitmap bmp = BitmapFactory.decodeStream(is);
        conn.disconnect();
        return bmp;
    }

    private String encode(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }
}
