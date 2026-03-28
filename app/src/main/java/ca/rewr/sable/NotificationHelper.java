package ca.rewr.sable;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import android.app.Notification;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.content.LocusIdCompat;
import androidx.core.graphics.drawable.IconCompat;

public class NotificationHelper {

    // Per-type channels
    public static final String CHANNEL_ID_DM    = "sable_dm";
    public static final String CHANNEL_ID_GROUP = "sable_group_v2";
    public static final String CHANNEL_ID_MISC  = "sable_misc";

    /** Legacy channel ID kept for SyncService / re-login notifications already using it. */
    public static final String CHANNEL_ID = CHANNEL_ID_DM;

    public static final String GROUP_KEY = "ca.rewr.sable.MESSAGES";
    private static final int GROUP_SUMMARY_ID = 0;

    private final Context context;
    private final ConversationShortcutHelper shortcutHelper;
    private final MessageHistory messageHistory = new MessageHistory();

    public NotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.shortcutHelper = new ConversationShortcutHelper(context);
        createChannels();
    }

    // ── Channel setup ──────────────────────────────────────────────────────────

    private void createChannels() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel dm = new NotificationChannel(
            CHANNEL_ID_DM, "Direct Messages", NotificationManager.IMPORTANCE_HIGH);
        dm.setDescription("1-on-1 direct messages");
        dm.enableVibration(true);
        dm.setShowBadge(true);
        nm.createNotificationChannel(dm);

        // Delete old low-importance group channel if it exists (importance can't be changed after creation)
        nm.deleteNotificationChannel("sable_group");

        NotificationChannel group = new NotificationChannel(
            CHANNEL_ID_GROUP, "Group Rooms", NotificationManager.IMPORTANCE_HIGH);
        group.setDescription("Messages in group rooms");
        group.enableVibration(true);
        group.setShowBadge(true);
        nm.createNotificationChannel(group);

        NotificationChannel misc = new NotificationChannel(
            CHANNEL_ID_MISC, "Other", NotificationManager.IMPORTANCE_DEFAULT);
        misc.setDescription("System and miscellaneous notifications");
        misc.setShowBadge(false);
        nm.createNotificationChannel(misc);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Stable notification ID for a given room. Public so MarkReadReceiver can cancel. */
    public static int notifIdForRoom(String roomId) {
        return (CHANNEL_ID_DM + roomId).hashCode();
    }

    private String channelFor(boolean isDm) {
        return isDm ? CHANNEL_ID_DM : CHANNEL_ID_GROUP;
    }

    // ── Main show-message entry point ──────────────────────────────────────────

    public void showMessage(String roomId, String roomName, String senderName,
                            Bitmap senderAvatar, Bitmap roomAvatar, String body,
                            boolean isEncrypted, boolean isDm) {
        showMessage(roomId, roomName, senderName, senderAvatar, roomAvatar, body,
            isEncrypted, isDm, /*latestEventId=*/null);
    }

    public void showMessage(String roomId, String roomName, String senderName,
                            Bitmap senderAvatar, Bitmap roomAvatar, String body,
                            boolean isEncrypted, boolean isDm, String latestEventId) {

        // ── Tap intent ─────────────────────────────────────────────────────────
        Intent tapIntent = new Intent(context, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        tapIntent.putExtra("room_id", roomId);
        tapIntent.putExtra("is_dm", isDm);
        PendingIntent tapPi = PendingIntent.getActivity(context, roomId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String messageText = isEncrypted ? "New message" : body;

        // ── Avatars ────────────────────────────────────────────────────────────
        // largeIcon: room avatar for groups, sender avatar for DMs (shown collapsed)
        Bitmap baseAvatar = isDm ? senderAvatar : (roomAvatar != null ? roomAvatar : senderAvatar);
        Bitmap roundedAvatar = AvatarHelper.forNotification(baseAvatar);
        // senderIcon: always the sender's avatar (shown per-message in expanded view).
        // Fall back to an initials avatar so each Person in MessagingStyle has a distinct
        // icon — without this, Android falls back to the notification largeIcon (room pic).
        Bitmap senderBitmap = senderAvatar != null ? senderAvatar : AvatarHelper.initialsAvatar(senderName);
        Bitmap roundedSenderAvatar = AvatarHelper.forNotification(senderBitmap);

        // ── Shortcut ───────────────────────────────────────────────────────────
        shortcutHelper.pushShortcut(roomId, isDm ? senderName : roomName, baseAvatar, !isDm);

        // ── MessagingStyle ─────────────────────────────────────────────────────
        // Recover existing style from the active notification so messages accumulate
        // across FCM pushes (process dies between invocations, in-memory history is gone).
        Person selfPerson = new Person.Builder().setName("You").build();
        NotificationCompat.MessagingStyle style = null;
        int notifId = notifIdForRoom(roomId);

        try {
            NotificationManager systemNm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (systemNm != null) {
                for (StatusBarNotification sbn : systemNm.getActiveNotifications()) {
                    if (sbn.getId() == notifId) {
                        Notification existing = sbn.getNotification();
                        style = NotificationCompat.MessagingStyle
                            .extractMessagingStyleFromNotification(existing);
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}

        if (style == null) {
            // No active notification — seed from in-process history (SyncService path)
            messageHistory.add(roomId, senderName, messageText, roundedSenderAvatar);
            style = new NotificationCompat.MessagingStyle(selfPerson)
                .setConversationTitle(isDm ? null : roomName)
                .setGroupConversation(!isDm);
            for (MessageHistory.Message msg : messageHistory.get(roomId)) {
                Person.Builder pb = new Person.Builder().setName(msg.senderName);
                if (msg.avatar != null) pb.setIcon(IconCompat.createWithBitmap(msg.avatar));
                style.addMessage(msg.body, msg.timestamp, pb.build());
            }
        } else {
            // Active notification found — append new message to recovered style
            Person.Builder pb = new Person.Builder().setName(senderName);
            if (roundedSenderAvatar != null) pb.setIcon(IconCompat.createWithBitmap(roundedSenderAvatar));
            style.addMessage(messageText, System.currentTimeMillis(), pb.build());
            messageHistory.add(roomId, senderName, messageText, roundedSenderAvatar);
        }

        // Mark as read
        Intent markReadIntent = new Intent(context, MarkReadReceiver.class);
        markReadIntent.setAction(MarkReadReceiver.ACTION);
        markReadIntent.putExtra(MarkReadReceiver.EXTRA_ROOM_ID, roomId);
        if (latestEventId != null) markReadIntent.putExtra(MarkReadReceiver.EXTRA_EVENT_ID, latestEventId);
        PendingIntent markReadPi = PendingIntent.getBroadcast(context,
            ("mark_read" + roomId).hashCode(), markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action markReadAction = new NotificationCompat.Action.Builder(
            R.drawable.ic_notification, "Mark as read", markReadPi).build();

        // Quick reply (RemoteInput)
        RemoteInput remoteInput = new RemoteInput.Builder(ReplyReceiver.KEY_TEXT_REPLY)
            .setLabel("Reply…")
            .build();
        Intent replyIntent = new Intent(context, ReplyReceiver.class);
        replyIntent.setAction(ReplyReceiver.ACTION);
        replyIntent.putExtra(ReplyReceiver.EXTRA_ROOM_ID, roomId);
        replyIntent.putExtra(ReplyReceiver.EXTRA_NOTIF_ID, notifId);
        PendingIntent replyPi = PendingIntent.getBroadcast(context,
            ("reply" + roomId).hashCode(), replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
            R.drawable.ic_notification, "Reply", replyPi)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build();

        // ── Build notification ─────────────────────────────────────────────────
        String channelId = channelFor(isDm);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setShortcutId(roomId)
            .setLocusId(new LocusIdCompat(roomId))
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .addAction(replyAction)
            .addAction(markReadAction);

        if (roundedAvatar != null) builder.setLargeIcon(roundedAvatar);

        // ── Bubble metadata (Android 11+) ──────────────────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent bubbleIntent = new Intent(context, BubbleActivity.class);
            bubbleIntent.putExtra(BubbleActivity.EXTRA_ROOM_ID, roomId);
            if (isDm) bubbleIntent.putExtra(BubbleActivity.EXTRA_USER_ID, "");
            PendingIntent bubblePi = PendingIntent.getActivity(context,
                ("bubble_" + roomId).hashCode(), bubbleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            IconCompat bubbleIcon = roundedAvatar != null
                ? IconCompat.createWithBitmap(roundedAvatar)
                : IconCompat.createWithResource(context, R.drawable.ic_notification);

            NotificationCompat.BubbleMetadata bubble = new NotificationCompat.BubbleMetadata.Builder(
                    bubblePi, bubbleIcon)
                .setDesiredHeight(600)
                .setSuppressNotification(false)
                .build();

            builder.setBubbleMetadata(bubble);
        }

        // ── Count active message notifications for badge + summary ─────────────
        try {
            NotificationManagerCompat nm = NotificationManagerCompat.from(context);
            nm.notify(notifId, builder.build());

            NotificationManager systemNm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            StatusBarNotification[] active = systemNm != null
                ? systemNm.getActiveNotifications() : new StatusBarNotification[0];

            long messageNotifCount = 0;
            for (StatusBarNotification sbn : active) {
                String ch = sbn.getNotification().getChannelId();
                if ((CHANNEL_ID_DM.equals(ch) || CHANNEL_ID_GROUP.equals(ch))
                        && sbn.getId() != GROUP_SUMMARY_ID) {
                    messageNotifCount++;
                }
            }

            // Set badge count on the summary (launchers pick this up)
            if (messageNotifCount >= 2) {
                NotificationCompat.Builder summary = new NotificationCompat.Builder(context, CHANNEL_ID_MISC)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(Config.APP_DISPLAY_NAME)
                    .setContentText(messageNotifCount + " new notifications")
                    .setNumber((int) messageNotifCount)
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

    // ── Clear ──────────────────────────────────────────────────────────────────

    public void clearNotification(String roomId) {
        NotificationManagerCompat.from(context).cancel(notifIdForRoom(roomId));
        messageHistory.clear(roomId);
    }

    public void clearAll() {
        NotificationManagerCompat.from(context).cancelAll();
        messageHistory.clearAll();
    }

    // ── showRoomNotification (JS bridge, foreground) ───────────────────────────

    public void showRoomNotification(String title, String body, String tag,
                                     String roomId, String userId, String eventId) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("room_id", roomId);
        intent.putExtra("user_id", userId);
        PendingIntent pi = PendingIntent.getActivity(context, roomId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID_DM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH);

        try {
            NotificationManagerCompat.from(context).notify(
                (CHANNEL_ID_DM + tag).hashCode(), builder.build());
        } catch (SecurityException ignored) {}
    }

    // ── showNotification (legacy JS bridge) ───────────────────────────────────

    public void showNotification(String title, String body, String tag) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, tag.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID_MISC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        try {
            NotificationManagerCompat.from(context).notify(
                (CHANNEL_ID_MISC + tag).hashCode(), builder.build());
        } catch (SecurityException ignored) {}
    }
}
