package ca.rewr.sable;

import android.content.Context;
import android.util.Log;

/**
 * Full (Google/FCM) flavor push provider.
 * Retrieves the FCM token and registers it as a Sygnal pusher with Synapse.
 */
public class PushProviderImpl implements PushProvider {

    private static final String TAG = "PushProviderFull";

    @Override
    public void init(Context context, TokenStore tokenStore) {
        registerIfNeeded(context, tokenStore);
    }

    @Override
    public void onSessionChanged(Context context, TokenStore tokenStore) {
        registerIfNeeded(context, tokenStore);
    }

    private void registerIfNeeded(Context context, TokenStore tokenStore) {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token -> {
                if (token == null) return;
                tokenStore.saveFcmToken(token);
                if (tokenStore.hasSession() && !tokenStore.isFcmPusherRegistered()) {
                    FcmPushService.registerPusherFromContext(context, tokenStore, token);
                }
            })
            .addOnFailureListener(e -> Log.w(TAG, "FCM token fetch failed: " + e.getMessage()));
    }
}
