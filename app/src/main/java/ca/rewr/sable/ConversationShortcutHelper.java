package ca.rewr.sable;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

import androidx.core.app.Person;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

/**
 * Creates and maintains dynamic conversation shortcuts (Android 11+).
 * These power the avatar in the collapsed notification view.
 */
public class ConversationShortcutHelper {

    private final Context context;

    public ConversationShortcutHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Push a conversation shortcut for a room.
     * Must be called before posting the notification.
     *
     * @param roomId      Unique ID for the conversation
     * @param roomName    Display name shown in the shortcut
     * @param avatar      Avatar bitmap (sender for DMs, room icon for groups)
     * @param isGroup     Whether this is a group conversation
     */
    public void pushShortcut(String roomId, String roomName, Bitmap avatar, boolean isGroup) {
        try {
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.setAction(Intent.ACTION_VIEW);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            Person.Builder personBuilder = new Person.Builder()
                .setName(roomName)
                .setImportant(true);

            if (avatar != null) {
                Bitmap rounded = AvatarHelper.forNotification(avatar);
                if (rounded != null) {
                    personBuilder.setIcon(IconCompat.createWithBitmap(rounded));
                }
            }

            ShortcutInfoCompat.Builder shortcutBuilder = new ShortcutInfoCompat.Builder(context, roomId)
                .setLongLived(true)
                .setIntent(launchIntent)
                .setShortLabel(roomName)
                .setLongLabel(roomName)
                .setPerson(personBuilder.build())
                .setIsConversation();

            if (avatar != null) {
                Bitmap rounded = AvatarHelper.forNotification(avatar);
                if (rounded != null) {
                    shortcutBuilder.setIcon(IconCompat.createWithBitmap(rounded));
                }
            }

            ShortcutManagerCompat.pushDynamicShortcut(context, shortcutBuilder.build());
        } catch (Exception e) {
            // Ignore — shortcut API not available or quota exceeded
        }
    }
}
