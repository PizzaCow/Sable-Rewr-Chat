(function() {
  if (!window.AndroidNotifications) return;

  // --- Notification override ---
  function AndroidNotificationShim(title, options) {
    options = options || {};
    const body = options.body || '';
    const tag = options.tag || String(Date.now());
    const data = options.data || {};
    const roomId = (data && data.room_id) ? data.room_id : '';
    const userId = (data && data.user_id) ? data.user_id : '';
    const eventId = (data && data.event_id) ? data.event_id : '';
    if (roomId) {
      if (userId) {
        window.AndroidNotifications.saveOwnUserId(userId);
      }
      window.AndroidNotifications.showRoomNotification(title, body, tag, roomId, userId, eventId);
    } else {
      window.AndroidNotifications.showNotification(title, body, tag);
    }
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
        return Promise.resolve({ scope: '/' });
      });
    };
  }

  // --- Session token extraction ---

  var sessionSaved = false;

  function saveIfFound(token, homeserver) {
    if (token && !sessionSaved) {
      sessionSaved = true;
      homeserver = homeserver || 'https://matrix.rewr.ca';
      console.log('[SableAndroid] Found token, saving session');
      window.AndroidNotifications.saveSession(token, homeserver);
    }
  }

  // 1. Check localStorage (various key patterns)
  function tryLocalStorage() {
    try {
      // Direct known keys (Element/Cinny style)
      var knownKeys = ['mx_access_token', 'cinny_access_token', 'sable_access_token'];
      for (var k of knownKeys) {
        var val = localStorage.getItem(k);
        if (val) {
          var hs = localStorage.getItem('mx_hs_url') || localStorage.getItem('cinny_hs_url') || 'https://matrix.rewr.ca';
          saveIfFound(val, hs);
          return true;
        }
      }
      // Scan all keys for JSON objects containing access_token
      for (var i = 0; i < localStorage.length; i++) {
        var key = localStorage.key(i);
        var raw = localStorage.getItem(key);
        if (!raw || raw[0] !== '{') continue;
        try {
          var obj = JSON.parse(raw);
          if (obj && obj.access_token) {
            var hs = obj.baseUrl || obj.homeserver || obj.base_url || 'https://matrix.rewr.ca';
            saveIfFound(obj.access_token, hs);
            return true;
          }
        } catch(e) {}
      }
    } catch(e) {}
    return false;
  }

  // 2. Check IndexedDB (Cinny/Sable stores session here)
  function tryIndexedDB() {
    if (!window.indexedDB) return;
    // List all databases and scan for auth data
    var dbNames = ['cinny', 'sable', 'matrix-js-sdk:crypto', 'riot-web-sync', 'matrix-sdk-crypto'];
    
    // Try indexedDB.databases() if available
    if (indexedDB.databases) {
      indexedDB.databases().then(function(dbs) {
        dbs.forEach(function(db) { scanIndexedDB(db.name); });
      }).catch(function(){});
    }
    // Also try known names directly
    dbNames.forEach(function(name) { scanIndexedDB(name); });
  }

  function scanIndexedDB(dbName) {
    if (sessionSaved) return;
    try {
      var req = indexedDB.open(dbName);
      req.onsuccess = function(e) {
        var db = e.target.result;
        var stores = Array.from(db.objectStoreNames);
        stores.forEach(function(storeName) {
          try {
            var tx = db.transaction(storeName, 'readonly');
            var store = tx.objectStore(storeName);
            var getAll = store.getAll ? store.getAll() : null;
            if (getAll) {
              getAll.onsuccess = function(e) {
                var items = e.target.result || [];
                items.forEach(function(item) {
                  if (!item || sessionSaved) return;
                  // Look for access_token fields
                  if (item.access_token) {
                    saveIfFound(item.access_token, item.baseUrl || item.homeserver || 'https://matrix.rewr.ca');
                  }
                  // Some stores use key/value pairs
                  if (item.key === 'access_token' || item.key === 'mx_access_token') {
                    saveIfFound(item.value, 'https://matrix.rewr.ca');
                  }
                });
              };
            }
          } catch(e) {}
        });
      };
      req.onerror = function() {};
    } catch(e) {}
  }

  // 3. Intercept fetch/XHR to catch the token from Authorization headers
  // This is the most reliable method — catches the token the moment Sable uses it
  var _fetch = window.fetch;
  window.fetch = function(input, init) {
    if (!sessionSaved && init && init.headers) {
      var headers = init.headers;
      var auth = null;
      if (headers instanceof Headers) {
        auth = headers.get('Authorization');
      } else if (typeof headers === 'object') {
        auth = headers['Authorization'] || headers['authorization'];
      }
      if (auth && auth.startsWith('Bearer ')) {
        var token = auth.substring(7);
        var url = typeof input === 'string' ? input : (input.url || '');
        var hs = url.match(/(https?:\/\/[^/]+)/);
        saveIfFound(token, hs ? hs[1] : 'https://matrix.rewr.ca');
      }
    }
    return _fetch.apply(this, arguments);
  };

  // Also intercept XMLHttpRequest
  var _open = XMLHttpRequest.prototype.open;
  var _setHeader = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
    if (!sessionSaved && name.toLowerCase() === 'authorization' && value.startsWith('Bearer ')) {
      saveIfFound(value.substring(7), 'https://matrix.rewr.ca');
    }
    return _setHeader.apply(this, arguments);
  };

  // Start extraction attempts
  tryLocalStorage();
  tryIndexedDB();
  setTimeout(function() { tryLocalStorage(); tryIndexedDB(); }, 3000);
  setTimeout(function() { tryLocalStorage(); tryIndexedDB(); }, 8000);

  window.addEventListener('storage', function() {
    setTimeout(tryLocalStorage, 500);
  });

  // Track current room so we can suppress notifications when user is already there
  function updateCurrentRoom() {
    var hash = window.location.hash || '';
    var match = hash.match(/\/(!\w+:[^/]+)\//);
    if (match && window.AndroidNotifications && window.AndroidNotifications.setCurrentRoom) {
      window.AndroidNotifications.setCurrentRoom(decodeURIComponent(match[1]));
    }
  }
  window.addEventListener('hashchange', updateCurrentRoom);
  updateCurrentRoom();

})();
