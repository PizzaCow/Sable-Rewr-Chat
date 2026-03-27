package ca.rewr.sable;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

public class AvatarHelper {

    /**
     * Generates a circular avatar with the sender's initial on a deterministic colour.
     * Used as a fallback when a user has no profile picture, so MessagingStyle always
     * has a distinct icon per-sender and never falls back to the room's largeIcon.
     */
    public static Bitmap initialsAvatar(String name) {
        int size = 96;
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        // Deterministic colour palette (Material 700-ish)
        int[] palette = {
            0xFF1976D2, 0xFF388E3C, 0xFFD32F2F,
            0xFF7B1FA2, 0xFFF57C00, 0xFF0097A7,
            0xFF5D4037, 0xFF455A64
        };
        int color = palette[Math.abs((name != null ? name : "").hashCode()) % palette.length];

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);

        String initial = (name != null && !name.isEmpty())
            ? String.valueOf(name.charAt(0)).toUpperCase(java.util.Locale.ROOT)
            : "?";

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(android.graphics.Color.WHITE);
        textPaint.setTextSize(size * 0.45f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        android.graphics.Rect bounds = new android.graphics.Rect();
        textPaint.getTextBounds(initial, 0, initial.length(), bounds);
        float y = size / 2f + bounds.height() / 2f - bounds.bottom;

        canvas.drawText(initial, size / 2f, y, textPaint);
        return output;
    }

    /**
     * Returns a new Bitmap with rounded corners.
     * @param radius corner radius in pixels (e.g. 24 for slightly rounded, bitmap.width/2 for circle)
     */
    public static Bitmap roundCorners(Bitmap src, float radius) {
        if (src == null) return null;
        Bitmap output = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        Paint paint = new Paint();
        paint.setAntiAlias(true);

        Rect rect = new Rect(0, 0, src.getWidth(), src.getHeight());
        RectF rectF = new RectF(rect);

        // Draw rounded rect as mask
        canvas.drawRoundRect(rectF, radius, radius, paint);

        // Apply src bitmap using SRC_IN (only draw where mask is)
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, rect, rect, paint);

        return output;
    }

    /** Circular crop for notification avatars */
    public static Bitmap forNotification(Bitmap src) {
        if (src == null) return null;
        float radius = src.getWidth() / 2f; // full circle
        return roundCorners(src, radius);
    }

    /**
     * Composites a small app badge onto the bottom-right of the avatar.
     * Mimics WhatsApp/Telegram style notification icons.
     */
    public static Bitmap withAppBadge(Bitmap avatar, Bitmap appIcon) {
        if (avatar == null) return null;
        if (appIcon == null) return avatar;

        int size = avatar.getWidth();
        int badgeSize = size / 3; // badge is 1/3 the size of the avatar
        int badgeX = size - badgeSize;
        int badgeY = size - badgeSize;

        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        // Draw avatar
        canvas.drawBitmap(avatar, 0, 0, null);

        // Scale app icon to badge size
        Bitmap scaledBadge = Bitmap.createScaledBitmap(appIcon, badgeSize, badgeSize, true);

        // Draw white circle background for the badge
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(android.graphics.Color.WHITE);
        canvas.drawCircle(badgeX + badgeSize / 2f, badgeY + badgeSize / 2f,
            badgeSize / 2f + 2, bgPaint);

        // Draw badge as circle
        Bitmap circleBadge = roundCorners(scaledBadge, badgeSize / 2f);
        canvas.drawBitmap(circleBadge, badgeX, badgeY, null);

        return output;
    }
}
