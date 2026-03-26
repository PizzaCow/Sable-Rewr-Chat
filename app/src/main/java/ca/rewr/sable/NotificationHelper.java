package ca.rewr.sable;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.graphics.drawable.IconCompat;

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

    /**
     * Show a Telegram-style MessagingStyle notification for a room.
     *
     * @param roomId      Room ID (used as notification tag)
     * @param roomName    Human-readable room name
     * @param senderName  Display name of the sender
     * @param senderAvatar Avatar bitmap for the sender (nullable)
     * @param body        Message body
     * @param isEncrypted Whether the message is encrypted (show generic body)
     */
    public void showMessage(String roomId, String roomName, String senderName,
                            Bitmap senderAvatar, String body, boolean isEncrypted) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, roomId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Build sender Person with avatar
        Person.Builder personBuilder = new Person.Builder().setName(senderName);
        if (senderAvatar != null) {
            personBuilder.setIcon(IconCompat.createWithBitmap(senderAvatar));
        }
        Person sender = personBuilder.build();

        String messageText = isEncrypted ? "🔒 New message" : body;
        long timestamp = System.currentTimeMillis();

        NotificationCompat.MessagingStyle style =
            new NotificationCompat.MessagingStyle(new Person.Builder().setName("You").build())
                .setConversationTitle(roomName)
                .addMessage(messageText, timestamp, sender);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setNumber(1);

        // Use sender avatar as large icon if available
        if (senderAvatar != null) {
            builder.setLargeIcon(senderAvatar);
        }

        try {
            NotificationManagerCompat.from(context).notify(
                (CHANNEL_ID + roomId).hashCode(), builder.build());
        } catch (SecurityException ignored) {}
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

        Person sender = new Person.Builder().setName(title).build();
        NotificationCompat.MessagingStyle style =
            new NotificationCompat.MessagingStyle(new Person.Builder().setName("You").build())
                .setConversationTitle(title)
                .addMessage(body, System.currentTimeMillis(), sender);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH);

        try {
            NotificationManagerCompat.from(context).notify(
                (CHANNEL_ID + tag).hashCode(), builder.build());
        } catch (SecurityException ignored) {}
    }
}
