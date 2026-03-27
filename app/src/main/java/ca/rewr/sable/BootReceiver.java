package ca.rewr.sable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            TokenStore store = new TokenStore(context);
            if (store.hasSession()) {
                Intent service = new Intent(context, SyncService.class);
                ContextCompat.startForegroundService(context, service);
                SyncService.scheduleWatchdog(context);
            }
        }
    }
}
