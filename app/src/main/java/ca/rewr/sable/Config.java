package ca.rewr.sable;

/**
 * Centralized configuration constants.
 * All URLs and app identifiers live here instead of scattered across classes.
 */
public final class Config {

    private Config() {} // no instances

    // ── Web app ────────────────────────────────────────────────────────────────
    public static final String SABLE_URL = "https://chat.rewr.ca";

    // ── Matrix homeserver (default) ────────────────────────────────────────────
    public static final String DEFAULT_HOMESERVER = "https://matrix.rewr.ca";

    // ── Sygnal push gateway ────────────────────────────────────────────────────
    public static final String SYGNAL_URL = "https://sygnal.rewr.ca/_matrix/push/v1/notify";

    // ── Deep link / App Link hosts ─────────────────────────────────────────────
    public static final String HOST_CHAT = "chat.rewr.ca";
    public static final String HOST_REWR_CHAT = "rewr.chat";
    public static final String HOST_DEEP_LINK = "to.rewr.chat";
    public static final String HOST_ACCOUNT = "account.rewr.ca";
    public static final String HOST_MATRIX = "matrix.rewr.ca";

    // ── App identifiers ────────────────────────────────────────────────────────
    public static final String APP_ID = "ca.rewr.sable";
    public static final String APP_DISPLAY_NAME = "Rewr.chat";
    public static final String CALLBACK_SCHEME = "ca.rewr.sable";
    public static final String CALLBACK_HOST = "callback";

    // ── Trusted origins for JS bridge calls ────────────────────────────────────
    /** Origins allowed to call saveSession() via the JS bridge. */
    public static final String[] TRUSTED_ORIGINS = {
        "https://chat.rewr.ca",
        "https://rewr.chat",
        "https://matrix.rewr.ca"
    };
}
