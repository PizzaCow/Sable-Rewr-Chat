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

    /** Slightly rounded — matches modern Android notification style */
    public static Bitmap forNotification(Bitmap src) {
        if (src == null) return null;
        float radius = src.getWidth() * 0.2f; // 20% radius = slightly rounded
        return roundCorners(src, radius);
    }
}
