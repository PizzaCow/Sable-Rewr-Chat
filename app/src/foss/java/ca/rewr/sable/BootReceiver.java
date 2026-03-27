package ca.rewr.sable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * Starts NtfyPushService on device boot if the user has an active session.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        TokenStore tokenStore = new TokenStore(context);
        if (tokenStore.hasSession()) {
            Intent serviceIntent = new Intent(context, NtfyPushService.class);
            ContextCompat.startForegroundService(context, serviceIntent);
        }
    }
}
