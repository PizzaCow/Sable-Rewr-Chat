(function() {
  if (!window.AndroidNotifications) return;

  // --- Notification override ---
  function AndroidNotificationShim(title, options) {
    options = options || {};
    const body = options.body || '';
    const tag = options.tag || String(Date.now());
    window.AndroidNotifications.showNotification(title, body, tag);
    this.title = title;
    this.body = body;
    this.tag = tag;
    this.close = function() { window.AndroidNotifications.closeNotification(tag); };
    this.onclick = null; this.onclose = null; this.onerror = null; this.onshow = null;
  }
  AndroidNotificationShim.permission = 'granted';
  AndroidNotificationShim.requestPermission = function(cb) {
    if (typeof cb === 'function') cb('granted');
    return Promise.resolve('granted');
  };
  AndroidNotificationShim.prototype = Object.create(EventTarget.prototype);
  window.Notification = AndroidNotificationShim;

  // Suppress SW errors
  if (navigator.serviceWorker) {
    const orig = navigator.serviceWorker.register.bind(navigator.serviceWorker);
    navigator.serviceWorker.register = function(url, opts) {
      return orig(url, opts).catch(function(e) {
        console.log('[SableAndroid] SW suppressed:', e);
        return Promise.resolve({ scope: '/' });
      });
    };
  }

  // --- Session token extraction ---
  // Sable (Cinny-based) stores the access token in IndexedDB/localStorage
  // Try to find and forward the token to the Android background sync service
  function tryExtractToken() {
    try {
      // Cinny stores data as JSON in localStorage under various keys
      for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        const val = localStorage.getItem(key);
        if (!val) continue;
        try {
          const obj = JSON.parse(val);
          // Look for access_token pattern
          if (obj && obj.access_token && typeof obj.access_token === 'string') {
            const hs = obj.baseUrl || obj.homeserver || obj.well_known?.['m.homeserver']?.base_url || 'https://matrix.rewr.ca';
            window.AndroidNotifications.saveSession(obj.access_token, hs);
            return true;
          }
        } catch(e) {}
      }
    } catch(e) {}
    return false;
  }

  // Try immediately, then retry after a delay (in case login just completed)
  if (!tryExtractToken()) {
    setTimeout(tryExtractToken, 3000);
    setTimeout(tryExtractToken, 8000);
  }

  // Also re-check after any storage changes (catches login events)
  window.addEventListener('storage', function() {
    setTimeout(tryExtractToken, 500);
  });
})();
