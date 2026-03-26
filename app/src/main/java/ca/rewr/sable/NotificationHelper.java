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
import androidx.core.app.Person;
import androidx.core.content.LocusIdCompat;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.graphics.drawable.IconCompat;

public class NotificationHelper {

    public static final String CHANNEL_ID = "sable_messages";
    private static final String CHANNEL_NAME = "Messages";
    public static final String GROUP_KEY = "ca.rewr.sable.MESSAGES";
    private static final int GROUP_SUMMARY_ID = 0;

    private final Context context;
    private final ConversationShortcutHelper shortcutHelper;
    private final MessageHistory messageHistory = new MessageHistory();

    public NotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.shortcutHelper = new ConversationShortcutHelper(context);
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
        intent.putExtra("room_id", roomId);
        intent.putExtra("is_dm", isDm);
        PendingIntent pi = PendingIntent.getActivity(context, roomId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String messageText = isEncrypted ? "New message" : body;

        // Pick the right avatar
        Bitmap baseAvatar = isDm
            ? senderAvatar
            : (roomAvatar != null ? roomAvatar : senderAvatar);
        Bitmap roundedAvatar = AvatarHelper.forNotification(baseAvatar);

        // Push conversation shortcut (required for avatar to show in collapsed view on Android 11+)
        shortcutHelper.pushShortcut(roomId, isDm ? senderName : roomName, baseAvatar, !isDm);

        // Store message in history (up to 3 per room)
        messageHistory.add(roomId, senderName, messageText, roundedAvatar);

        // Build MessagingStyle with last 3 messages
        NotificationCompat.MessagingStyle style =
            new NotificationCompat.MessagingStyle(new Person.Builder().setName("You").build())
                .setConversationTitle(isDm ? null : roomName)
                .setGroupConversation(!isDm);

        for (MessageHistory.Message msg : messageHistory.get(roomId)) {
            Person.Builder pb = new Person.Builder().setName(msg.senderName);
            if (msg.avatar != null) pb.setIcon(IconCompat.createWithBitmap(msg.avatar));
            style.addMessage(msg.body, msg.timestamp, pb.build());
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setShortcutId(roomId)
            .setLocusId(new LocusIdCompat(roomId))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setNumber(1);

        // Also set largeIcon as fallback for older Android
        if (roundedAvatar != null) builder.setLargeIcon(roundedAvatar);

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
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Rewr.chat")
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
        messageHistory.clear(roomId);
    }

    /** Legacy method for JS-bridge foreground notifications */
    public void showNotification(String title, String body, String tag) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, tag.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
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
