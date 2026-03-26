(function() {
  // Only run if the Android bridge is present
  if (!window.AndroidNotifications) return;

  // Override the Notification constructor
  const OriginalNotification = window.Notification;

  function AndroidNotificationShim(title, options) {
    options = options || {};
    const body = options.body || '';
    const tag = options.tag || String(Date.now());
    window.AndroidNotifications.showNotification(title, body, tag);

    // Return a fake Notification-like object so callers don't crash
    this.title = title;
    this.body = body;
    this.tag = tag;
    this.close = function() {
      window.AndroidNotifications.closeNotification(tag);
    };
    this.onclick = null;
    this.onclose = null;
    this.onerror = null;
    this.onshow = null;
  }

  AndroidNotificationShim.permission = 'granted';
  AndroidNotificationShim.requestPermission = function(callback) {
    const result = 'granted';
    if (typeof callback === 'function') callback(result);
    return Promise.resolve(result);
  };

  // Preserve any event listener APIs
  AndroidNotificationShim.prototype = Object.create(EventTarget.prototype);

  window.Notification = AndroidNotificationShim;

  // Also intercept serviceWorker registration to suppress SW push errors
  if (navigator.serviceWorker) {
    const originalRegister = navigator.serviceWorker.register.bind(navigator.serviceWorker);
    navigator.serviceWorker.register = function(scriptURL, options) {
      return originalRegister(scriptURL, options).catch(function(err) {
        console.log('[SableAndroid] SW registration suppressed:', err);
        return Promise.resolve({ scope: '/' });
      });
    };
  }
})();
