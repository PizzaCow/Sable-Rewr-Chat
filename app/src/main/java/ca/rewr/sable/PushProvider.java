package ca.rewr.sable;

import android.content.Context;

/**
 * Abstraction over the push backend.
 * Full flavor: Firebase/FCM via Sygnal.
 * FOSS flavor: UnifiedPush (ntfy, Gotify, etc.).
 */
public interface PushProvider {

    /** Called once at startup. Initialise the push backend and register a pusher with Synapse. */
    void init(Context context, TokenStore tokenStore);

    /** Called when the user's Matrix session changes (login/logout). Re-register pusher if needed. */
    void onSessionChanged(Context context, TokenStore tokenStore);
}
