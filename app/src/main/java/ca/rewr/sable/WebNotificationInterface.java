package ca.rewr.sable;

import android.content.Context;
import android.webkit.JavascriptInterface;

/**
 * JavaScript bridge injected as window.AndroidNotifications.
 * Sable's Web Notification calls are intercepted by the JS shim
 * which forwards them here.
 */
public class WebNotificationInterface {

    private final NotificationHelper helper;

    public WebNotificationInterface(Context context) {
        this.helper = new NotificationHelper(context);
    }

    @JavascriptInterface
    public void showNotification(String title, String body, String tag) {
        if (title == null) title = "Sable";
        if (body == null) body = "";
        if (tag == null) tag = String.valueOf(System.currentTimeMillis());
        helper.showNotification(title, body, tag);
    }

    @JavascriptInterface
    public void closeNotification(String tag) {
        if (tag != null) helper.clearNotification(tag);
    }
}
