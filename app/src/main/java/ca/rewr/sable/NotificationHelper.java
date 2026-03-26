package ca.rewr.sable;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class NotificationHelper {

    public static final String CHANNEL_ID = "sable_messages";
    private static final String CHANNEL_NAME = "Messages";

    private final Context context;

    public NotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        createChannel();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Sable chat messages");
        channel.enableVibration(true);
        channel.setShowBadge(true);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    public void showNotification(String title, String body, String tag) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, tag.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(1);

        try {
            NotificationManagerCompat.from(context).notify(
                (CHANNEL_ID + tag).hashCode(), builder.build());
        } catch (SecurityException ignored) {}
    }

    public void clearNotification(String tag) {
        NotificationManagerCompat.from(context).cancel(
            (CHANNEL_ID + tag).hashCode());
    }
}
