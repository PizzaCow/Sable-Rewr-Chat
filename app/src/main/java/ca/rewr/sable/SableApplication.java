package ca.rewr.sable;

import android.app.Application;
import com.google.android.material.color.DynamicColors;

/**
 * Application subclass that applies Material You dynamic colors
 * on Android 12+ devices. On older devices this is a no-op.
 */
public class SableApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Apply wallpaper-derived dynamic colors to all activities
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
