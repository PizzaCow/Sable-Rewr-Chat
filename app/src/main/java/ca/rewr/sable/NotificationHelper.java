package ca.rewr.sable;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class NotificationHelper {

    public static final String CHANNEL_ID = "sable_messages";
    private static final String CHANNEL_NAME = "Messages";
    public static final String GROUP_KEY = "ca.rewr.sable.MESSAGES";
    private static final int GROUP_SUMMARY_ID = 0;

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

    public void showMessage(String roomId, String roomName, String senderName,
                            Bitmap senderAvatar, Bitmap roomAvatar, String body,
                            boolean isEncrypted, boolean isDm) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, roomId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String messageText = isEncrypted ? "New message" : body;

        // Pick the right avatar
        Bitmap baseAvatar = isDm
            ? senderAvatar
            : (roomAvatar != null ? roomAvatar : senderAvatar);
        Bitmap roundedAvatar = AvatarHelper.forNotification(baseAvatar);
        Bitmap badgedAvatar  = AvatarHelper.withAppBadge(roundedAvatar, getAppIconBitmap());

        // Simple notification — setLargeIcon always shows in collapsed view
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(isDm ? senderName : roomName)
            .setContentText(isDm ? messageText : senderName + ": " + messageText)
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText(isDm ? messageText : senderName + ": " + messageText)
                .setSummaryText(isDm ? null : roomName))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setNumber(1);

        if (badgedAvatar != null) builder.setLargeIcon(badgedAvatar);

        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(context);
            int notifId = (CHANNEL_ID + roomId).hashCode();
            nm.notify(notifId, builder.build());

            // Count active message notifications
            NotificationManager systemNm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
            StatusBarNotification[] active = systemNm != null
                ? systemNm.getActiveNotifications() : new StatusBarNotification[0];
            long messageNotifCount = 0;
            for (StatusBarNotification sbn : active) {
                if (CHANNEL_ID.equals(sbn.getNotification().getChannelId())
                        && sbn.getId() != GROUP_SUMMARY_ID) {
                    messageNotifCount++;
                }
            }

            if (messageNotifCount >= 2) {
                NotificationCompat.Builder summary = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Sable")
                    .setContentText(messageNotifCount + " new notifications")
                    .setGroup(GROUP_KEY)
                    .setGroupSummary(true)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
                nm.notify(GROUP_SUMMARY_ID, summary.build());
            } else {
                nm.cancel(GROUP_SUMMARY_ID);
            }
        } catch (SecurityException ignored) {}
    }

    private Bitmap getAppIconBitmap() {
        try {
            Drawable drawable = context.getPackageManager()
                .getApplicationIcon(context.getPackageName());
            Bitmap bmp = Bitmap.createBitmap(
                drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bmp;
        } catch (Exception e) { return null; }
    }

    public void clearNotification(String roomId) {
        NotificationManagerCompat.from(context).cancel(
            (CHANNEL_ID + roomId).hashCode());
    }

    /** Legacy method for JS-bridge foreground notifications */
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
            .setPriority(NotificationCompat.PRIORITY_HIGH);

        try {
            NotificationManagerCompat.from(context).notify(
                (CHANNEL_ID + tag).hashCode(), builder.build());
        } catch (SecurityException ignored) {}
    }
}
