package ca.rewr.sable;

import android.content.Context;
import android.util.Log;

import static org.unifiedpush.android.connector.ConstantsKt.INSTANCE_DEFAULT;
import org.unifiedpush.android.connector.UnifiedPush;

/**
 * FOSS flavor push provider — uses UnifiedPush.
 * No Firebase, no Google Play Services dependency.
 *
 * On startup:
 *   1. tryUseCurrentOrDefaultDistributor selects a saved distributor or the system default.
 *   2. register requests an endpoint from the chosen distributor.
 *   3. UnifiedPushService.onNewEndpoint fires asynchronously with the push URL.
 */
public class PushProviderImpl implements PushProvider {

    private static final String TAG = "PushProviderFoss";

    @Override
    public void init(Context context, TokenStore tokenStore) {
        try {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(context, success -> {
                if (success) {
                    UnifiedPush.register(
                        context,
                        INSTANCE_DEFAULT,
                        null,  // messageForDistributor
                        null   // vapid
                    );
                    Log.i(TAG, "UnifiedPush distributor found, registered");
                } else {
                    Log.w(TAG, "No UnifiedPush distributor available");
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "UnifiedPush init failed: " + e.getMessage());
        }

        // If we already have an endpoint and no pusher registered, re-register
        String endpoint = tokenStore.getUpEndpoint();
        if (endpoint != null && !endpoint.isEmpty()
                && tokenStore.hasSession()
                && !tokenStore.isUpPusherRegistered()) {
            UnifiedPushService.registerPusher(context, tokenStore, endpoint);
        }
    }

    @Override
    public void onSessionChanged(Context context, TokenStore tokenStore) {
        String endpoint = tokenStore.getUpEndpoint();
        if (endpoint != null && !endpoint.isEmpty() && tokenStore.hasSession()) {
            tokenStore.setUpPusherRegistered(false);
            UnifiedPushService.registerPusher(context, tokenStore, endpoint);
        }
    }
}
