package ca.rewr.sable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Boot receiver for full (FCM) flavor.
 * FCM handles its own reconnection lifecycle — nothing needed here.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // FCM manages its own connection; no action required on boot.
    }
}
