/*jshint globalstrict:true */
'use strict';

var global = this;
var api = api || require('./api');
var log = log || api.log;
var LZ = LZ || require('./lzstring.min').LZ;

var THREAD_BATCH_SIZE = 8;

var hostRe = /^https?:\/\/([^\/?#]+)/;
var emailRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/;

var tabsByUrl = {}; // normUrl => [tab]
var tabsByLocator = {}; // locator => [tab]
var threadReadAt = {}; // threadID => time string (only if read recently in this browser)
var timeouts = {}; // tabId => timeout identifier

// ===== Cached data from server

var pageData = {}; // normUrl => PageData
var notificationLists = {}; // normUrl => ThreadList (special keys: 'all', 'sent', 'unread')
var notificationsById = {}; // threadId => thread (notification JSON)
var messageData = {}; // threadId => [message, ...]; TODO: evict old threads from memory
var keepData = {};
var activityData = {};
var contactSearchCache;
var urlPatterns;
var guideData;

function clearDataCache() {
  log('[clearDataCache]');
  tabsByUrl = {};
  tabsByLocator = {};
  threadReadAt = {};
  for (var tabId in timeouts) {
    api.timers.clearTimeout(timeouts[tabId]);
    delete timeouts[tabId];
  }

  pageData = {};
  notificationLists = {};
  notificationsById = {};
  messageData = {};
  keepData = {};
  activityData = {};
  contactSearchCache = null;
  urlPatterns = null;
  guideData = null;
}

// ===== Error reporting

(function (ab) {
  ab.setProject('95815', '603568fe4a88c488b6e2d47edca59fc1');
  ab.addReporter(function airbrake(notice, opts) {
    var msg = notice.errors && notice.errors[0] && notice.errors[0].message && notice.errors[0].message;
    var noReport = ['disconnected port object', 'Cannot access a chrome-extension', 'executeScript failed'];
    var validError = noReport.every(function (i) { return msg.indexOf(i) === -1; });
    log.apply(null, ['#c00', '[airbrake]'].concat(notice.errors));
    if (validError) {
      notice.params = breakLoops(notice.params);
      notice.context.environment = api.isPackaged() && !api.mode.isDev() ? 'production' : 'development';
      notice.context.version = api.version;
      notice.context.userAgent = api.browser.userAgent;
      notice.context.userId = me && me.id;
      sendXhr('POST', 'https://api.airbrake.io/api/v3/projects/' + opts.projectId + '/notices?key=' + opts.projectKey, notice, {}, function (o) {
        log('[airbrake]', o && o.url);
      });
    }
  });
  api.timers.setTimeout(api.errors.init.bind(null, ab), 0);

  function breakLoops(obj) {
    var n = 0, seen = [];
    return visit(obj, 0);

    function visit(o, d) {
      if (typeof o !== 'object') return o;
      if (seen.indexOf(o) >= 0) return '[circular]';
      if (d >= 4) return '[too deep]';
      seen.push(o)

      var o2 = {};
      for (var k in o) {
        if (Object.prototype.hasOwnProperty.call(o, k)) {
          if (++n > 100) break;
          var v;
          try {
            v = o[k];
          } catch (e) {
            continue;
          }
          o2[k] = visit(v, d + 1);
        }
      }
      return o2;
    }
  }
}(this.Airbrake || require('./airbrake.min').Airbrake));

// ===== Types/Classes

var ThreadList = ThreadList || require('./threadlist').ThreadList;

function PageData(o) {
  this.update(o);
}
PageData.prototype = {
  update: function (o) {
    this.keeps = o.keeps || [];
    this.position = o.position;
    this.neverOnSite = o.neverOnSite;
    this.shown = o.shown;
    this.keepers = o.keepers || [];
    this.keepersTotal = o.keepersTotal || this.keepers.length;
    this.libraries = o.libraries || [];
    this.sources = o.sources || [];
  },
  howKept: function () {
    var keeps = this.keeps;
    if (keeps.length) {
      // Visibilities with a higher index have greater precedence
      var visibilityOptions = ['secret', 'organization', 'published', 'discoverable']
      // Use a mapping to allow old styles named -public/private
      var visibilityMap = {
        'secret': 'private',
        'organization': 'organization',
        'published': 'public',
        'discoverable': 'public'
      };
      var maxVisibilityIndex = -1;
      keeps.forEach(function (keep) {
        var visibility = (keep.libraryId ? keep.visibility : null);
        maxVisibilityIndex = Math.max(maxVisibilityIndex, visibilityOptions.indexOf(visibility));
      });
      return maxVisibilityIndex >= 0 ? visibilityMap[visibilityOptions[maxVisibilityIndex]] : 'other';
    }
    return null;
  },
  findKeep: function (libraryId) {
    return this.keeps.filter(libraryIdIs(libraryId))[0] || null;
  }
};

function insertUpdateChronologically(arr, o, timePropName) {
  var time = o[timePropName];
  for (var i = arr.length; i--;) { // newest first
    var el = arr[i];
    if (time >= el[timePropName]) {
      arr.splice(i + 1, 0, o);
      time = null;
    }
    if (o.id === el.id) {
      arr.splice(i, 1);
    }
  }
  if (time) {
    arr.unshift(o);
  }
}

// ===== Server requests

var httpMethodRe = /^(?:GET|HEAD|POST|PUT|DELETE)$/;
var httpHeaders = {'X-Kifi-Client': 'BE ' + api.version};

function ajax(service, method, uri, data, done, fail, progress) {  // method and uri are required
  if (httpMethodRe.test(service)) { // shift args if service is missing
    progress = fail, fail = done, done = data, data = uri, uri = method, method = service, service = 'api';
  }
  if (typeof data === 'function') {  // shift args if data is missing and done is present
    progress = fail, fail = done, done = data, data = null;
  }

  if (data && method === 'GET') {
    var a = [];
    for (var key in data) {
      if (data.hasOwnProperty(key)) {
        var val = data[key];
        if (val != null) {
          a.push(encodeURIComponent(key) + '=' + encodeURIComponent(val));
        }
      }
    }
    uri += (~uri.indexOf('?') ? '&' : '?') + a.join('&').replace(/%20/g, '+');
    data = null;
  }

  uri = serviceNameToUri(service) + uri;
  var headers = progress ? extend({Accept: 'text/plain'}, httpHeaders) :  httpHeaders;
  sendXhr(
    method, uri, data, headers, done,
    fail || (method === 'GET' ? onGetFail.bind(null, uri, headers, done, progress, 1) : null),
    progress);
}

function onGetFail(uri, headers, done, progress, failures, req) {
  if ([403,404].indexOf(req.status) < 0) {
    if (failures < 10) {
      var ms = failures * 2000;
      log('[onGetFail]', req.status, uri, failures, 'failure(s), will retry in', ms, 'ms');
      var fail = onGetFail.bind(null, uri, headers, done, progress, failures + 1);
      api.timers.setTimeout(
        sendXhr.bind(null, 'GET', uri, null, headers, done, fail, progress),
        ms);
    } else {
      log('[onGetFail]', req.status, uri, failures, 'failures, giving up');
    }
  }
}

function sendXhr(method, uri, data, headers, done, fail, progress) {
  var xhr = new api.xhr();
  xhr.open(method, uri, true);
  if (data != null && data !== '') {
    if (typeof data !== 'string') {
      data = JSON.stringify(data);
    }
    xhr.setRequestHeader('Content-Type', 'application/json; charset=utf-8');
  }
  for (var name in headers) {
    xhr.setRequestHeader(name, headers[name]);
  }
  xhr.addEventListener('loadend', onXhrLoadEnd.bind(xhr, done, fail));
  if (progress) {
    xhr.addEventListener('progress', onXhrProgress.bind(xhr, progress));
    xhr.responseType = 'text';
  } else {
    xhr.responseType = 'json';
  }
  xhr.send(data);
}

var onXhrLoadEnd = api.errors.wrap(function onXhrLoadEnd(done, fail) {
  var status = this.status;
  if (status >= 200 && status < 300) {
    if (done) done(status === 204 ? null : this.response);
  } else {
    if (fail) fail(this);
  }
});

var onXhrProgress = api.errors.wrap(function onXhrProgress(progress) {
  var status = this.status;
  if (status >= 200 && status < 300) {
    progress(this.response);
  }
});


// ===== Event logging

var tracker = {
  sendTimer: 0,
  queue: [],
  batch: [],
  consolidating: {},
  sendBatch: function () {
    if (this.batch.length > 0) {
      if (api.isPackaged() || api.mode.isDev()) {
        //ajax('POST', '/ext/events', this.batch);
      }
      this.batch.length = 0;
    }
  },
  augmentAndBatch: function (data) {
    data.properties.source = 'extension';
    data.properties.browser = api.browser.name;
    data.properties.browserDetails = api.browser.userAgent;
    this.batch.push(data);
    if (this.batch.length > 10) {
      this.sendBatch();
    }
  },
  track: function (eventName, properties) {
    if (!this.sendTimer) {
      this.sendTimer = api.timers.setInterval(this.sendBatch.bind(this), 60000);
    }
    log('#aaa', '[tracker.track] %s %o', eventName, properties);
    properties.time = Date.now();
    var data = {
      'event': eventName,
      'properties': properties
    };
    if (me) {
      this.augmentAndBatch(data);
    } else {
      this.queue.push(data);
    }
  },
  trackConsolidated: function (eventName, properties, prop, id, ms) {
    var t = api.timers.setTimeout(this._trackConsolidated.bind(this, id), ms);
    var o = this.consolidating[id];
    if (!o) {
      properties[prop] = 1;
      this.consolidating[id] = {
        timeout: t,
        eventName: eventName,
        properties: properties,
        prop: prop
      };
    } else {
      o.properties[o.prop]++;
      api.timers.clearTimeout(o.timeout);
      o.timeout = t;
    }
  },
  _trackConsolidated: function (id) {
    var o = this.consolidating[id];
    delete this.consolidating[id];
    this.track(o.eventName, o.properties);
  },
  catchUp: function () {
    var that = this;
    this.queue.forEach(function (data) {
      that.augmentAndBatch(data);
    });
    this.queue = [];
  }
};

function logEvent(eventFamily, eventName, metaData) {
  var ev = {
    installId: stored('installation_id'),
    eventFamily: eventFamily,
    eventName: eventName,
    metaData: metaData
  };
  log("#aaa", "[logEvent] %s %o", ev.eventName, ev);
  if (socket) {
    //socket.send(["log_event", ev]);
  } else {
    ev.time = Date.now();
    logEvent.queue.push(ev);
    if (logEvent.queue.length > 50) {
      logEvent.queue.shift();  // discard oldest
    }
  }
}
logEvent.queue = [];
logEvent.catchUp = function() {
  var t = Date.now();
  while (logEvent.queue.length) {
    var ev = logEvent.queue.shift();
    ev.msAgo = t - ev.time;
    delete ev.time;
    //socket.send(["log_event", ev]);
  }
}

// ===== WebSocket

function onSocketConnect() {
  getLatestThreads();

  // http data refresh
  getUrlPatterns(getPrefs.bind(null, api.noop));
}

function onSocketDisconnect(why, sec) {
  log('[onSocketDisconnect]', why, sec || '');
}

function getLatestThreads() {
  //socket.send(['get_latest_threads', THREAD_BATCH_SIZE], gotLatestThreads);
}

function gotLatestThreads(arr, numUnreadUnmuted, numUnread, serverTime) {
  log('[gotLatestThreads]', arr, numUnreadUnmuted, numUnread, serverTime);

  var serverTimeDate = new Date(serverTime);
  var staleMessageIds = (notificationLists.all || {ids: []}).ids.reduce(function (o, threadId) {
    o[notificationsById[threadId].id] = true;  // message ID, not thread ID
    return o;
  }, {});

  notificationsById = {};
  threadReadAt = {};
  arr.forEach(function (n) {
    standardizeNotification(n);
    notificationsById[n.thread] = notificationsById[n.thread] || n; // If server sends dupe notifications, keep the one that came down first
    var ageMs = serverTimeDate - new Date(n.time);
    if (ageMs >= 0 && ageMs < 60000 && !staleMessageIds[n.id]) {
      handleRealTimeNotification(n);
    }
  });

  notificationLists = {};
  notificationLists.all = new ThreadList(notificationsById, arr.map(getThreadId), null, numUnreadUnmuted);
  notificationLists.sent = new ThreadList(notificationsById, arr.filter(isSent).map(getThreadId));
  notificationLists.unread = new ThreadList(notificationsById, arr.filter(isUnread).map(getThreadId), numUnread);
  notificationLists.all.includesOldest = arr.length < THREAD_BATCH_SIZE;
  notificationLists.sent.includesOldest = arr.length < THREAD_BATCH_SIZE;
  notificationLists.unread.includesOldest = notificationLists.unread.ids.length >= numUnread;

  emitThreadsToTabsViewing('all', notificationLists.all);
  forEachTabAtThreadList(sendUnreadThreadCount);

  ['sent', 'unread'].forEach(function (kind) {
    var tl = notificationLists[kind];
    if (tl.includesOldest || tl.ids.length) {
      emitThreadsToTabsViewing(kind, tl);
    }
    if (!tl.includesOldest && tl.ids.length <= (kind === 'unread' ? 1 : 0)) {  // precaution to avoid ever having 0 of N unread threads loaded
      //socket.send(['get_' + kind + '_threads', THREAD_BATCH_SIZE], gotFilteredThreads.bind(null, kind, tl));
    }
  });

  tellVisibleTabsNoticeCountIfChanged();

  messageData = {};
  keepData = {};
  activityData = {};
  forEachThreadOpenInPane(function (threadId) {
    //socket.send(['get_thread', threadId]);
  });

  api.tabs.eachSelected(kifify);
}

function gotFilteredThreads(kind, tl, arr, numTotal) {
  log('[gotFilteredThreads]', kind, arr, numTotal || '');
  arr.forEach(function (n) {
    standardizeNotification(n);
    updateIfJustRead(n);
    notificationsById[n.thread] = n;
  });
  tl.ids = arr.map(getThreadId);
  tl.includesOldest = arr.length < THREAD_BATCH_SIZE;
  if (numTotal != null) {
    tl.numTotal = numTotal;
  }
  emitThreadsToTabsViewing(kind, tl);
}

var socketHandlers = {
  denied: function () {
    log('[socket:denied]');
    clearSession();
  },
  version: function (v) {
    log('[socket:version]', v);
    if (api.version !== v) {
      api.requestUpdateCheck();
    }
  },
  flush: function (t) {
    log('[socket:flush]', t);
    if (t === 'full') {
      clearSession();
    } else {
      lightFlush();
    }
  },
  experiments: function (exp) {
    log('[socket:experiments]', exp);
    experiments = exp;
    api.toggleLogging(exp.indexOf('extension_logging') >= 0);
  },
  event: function (keepId, activityEvent) {
    log('[socket:event]', keepId, activityEvent);

    var activity = activityData[keepId];
    if (activity && activity.events) {
      activity.events.unshift(activityEvent);
      forEachTabAtLocator('/messages/' + keepId, function (tab) {
        api.tabs.emit(tab, 'activity', { keepId: keepId, activity: activity });
      });
    }
  },
  new_pic: function (name) {
    log('[socket:new_pic]', name);
    if (me) {
      me.pictureName = name;
      emitAllTabs('me_change', me);
      for (var thId in messageData) {
        var arr = messageData[thId];
        for (var i = 0; i < arr.length; i++) {
          var m = arr[i];
          updatePic(m.user);
          m.participants.forEach(updatePic);
        }
      }
      for (var thId in notificationsById) {
        var th = notificationsById[thId];
        if (th.category === 'message') {
          updatePic(th.author);
          th.participants.forEach(updatePic);
        }
      }
    }
    function updatePic(u) {
      if (u && u.id === me.id) {
        u.pictureName = name;
      }
    }
  },
  new_friends: function (fr) {
    log('[socket:new_friends]', fr);
    contactSearchCache = null;
  },
  lost_friends: function (fr) {
    log('[socket:lost_friends]', fr);
    contactSearchCache = null;
  },
  thread_recipients: function (keepId, users, emails, libraries) {
    log('[socket:thread_recipients]', keepId, users, emails, libraries);
    users = users || [];
    emails = emails || [];
    libraries = libraries || [];

    emails = emails.map(function (email) {
      return (typeof email === 'string' ? { id: email, email: email } : email);
    });
    libraries.forEach(function (l) {
      l.kind = 'library';
    });

    var keep = keepData[keepId];
    if (keep) {
      if (keep.recipients.users.length > users.length && !users.some(idIs(me.id))) {
        removeNotification(keepId);
        forEachTabAtLocator('/messages/' + keepId, function (tab) {
          api.tabs.emit(tab, 'show_pane', {
            trigger: 'evictedAutoNavigate',
            locator: getDefaultPaneLocator(),
            redirected: false
          }, {queue: 1});
        });
        return;
      }
      keep.recipients.users = users;
      keep.recipients.emails = emails;
      keep.recipients.libraries = libraries;
      forEachTabAtLocator('/messages/' + keepId, function (tab) {
        api.tabs.emit(tab, 'recipients', { users: users, emails: emails, libraries: libraries });
      });
    }
  },
  thread_participants: function(threadId, participants) {
    log('[socket:thread_participants]', threadId, participants);
    var thread = notificationsById[threadId];
    if (thread) {
      thread.participants = participants;
    }
    forEachTabAtLocator('/messages/' + threadId, function (tab) {
      api.tabs.emit(tab, 'participants', participants);  // TODO: send threadId too
    });
  },
  removed_from_thread: function (keepId) {
    removeFromThread(keepId);
  },
  thread_muted: function(threadId, muted) {
    log("[socket:thread_muted]", threadId, muted);
    setMuted(threadId, muted);
  },
  url_patterns: function(patterns) {
    log("[socket:url_patterns]", patterns);
    urlPatterns = compilePatterns(patterns);
  },
  notification: function(n, th) {  // a new notification (real-time)
    log('[socket:notification]', n, th || '');
    standardizeNotification(n);
    if (insertNewNotification(th ? standardizeNotification(th) : n)) {
      handleRealTimeNotification(n);
      tellVisibleTabsNoticeCountIfChanged();
    }
  },
  remove_notification: function (th) {
    log('[socket:remove_notification]', th);
    removeNotification(th);
  },
  all_notifications_visited: function(id, time) {
    log('[socket:all_notifications_visited]', id, time);
    markAllThreadsRead(id, time);
  },
  thread: function(o) {
    log('[socket:thread]', o);
    var id = o.id;
    messageData[id] = o.messages;

    getKeepAndActivity(id)
    .then(function (responseData) {
      var keep = keepData[id] = responseData.keep;
      var activity = activityData[id] = responseData.activity;
      forEachTabAtLocator('/messages/' + id, emitThreadToTab.bind(null, id, keep, activity));
    })
    .catch(function (xhr) {
      log('#f00', '[socket:thread] error: ', err);
      forEachTabAtLocator('/messages/' + id, emitThreadErrorToTab.bind(null, id, xhr));
    });
  },
  message_read: function(nUri, threadId, time, messageId) {
    log("[socket:message_read]", nUri, threadId, time);
    removeNotificationPopups(threadId);
    markRead(threadId, messageId, time);
  },
  message_unread: function(nUri, threadId, time, messageId) {
    log("[socket:message_unread]", nUri, threadId, time);
    markUnread(threadId, messageId);
  },
  unread_notifications_count: function (unreadCount) {
    log('[socket:unread_notifications_count]', unreadCount, notificationLists && notificationLists.all && notificationLists.all.numUnreadUnmuted);
    if (notificationLists && notificationLists.all) {
       notificationLists.all.numUnreadUnmuted = unreadCount;
       // This forcefully updates the tabs to have the server's count. The issue is that the client
       // _usually_ does a good job being correct, so this introduces potential latiency. Right now,
       // if counts get off, if the user flips between tabs, it fixes. Good enough? Uncomment if no.
       //tellVisibleTabsNoticeCountIfChanged();
    }
  }
};

function emitAllTabs(name, data, options) {
  return api.tabs.each(function(tab) {
    api.tabs.emit(tab, name, data, options);
  });
}

function emitThreadToTab(id, keep, activity, tab) {
  api.tabs.emit(tab, 'thread', {id: id, keep: keep, activity: activity}, {queue: 1});
}

function emitThreadErrorToTab(id, xhr, tab) {
  var error = {status: xhr.status, message: xhr.response.error || xhr.statusText};
  api.tabs.emit(tab, 'thread_error', {id: id, error: error});
}

function emitThreadsToTab(kind, tl, tab) {
  var threads = tl.ids.slice(0, THREAD_BATCH_SIZE).map(idToThread);
  api.tabs.emit(tab, 'threads', {
    kind: kind,
    threads: threads,
    includesOldest: tl.includesOldest && tl.ids.length === threads.length
  }, {queue: 1});
}

function emitThreadsToTabsViewing(kind, tl) {
  forEachTabAtLocator('/messages:' + kind, emitThreadsToTab.bind(null, kind, tl));
}

function emitSettings(tab) {
  api.tabs.emit(tab, 'settings', {
    sounds: enabled('sounds'),
    popups: enabled('popups'),
    emails: prefs ? prefs.messagingEmails : true,
    social: prefs ? !prefs.hideSocialTooltip : true,
    keeper: enabled('keeper'),
    search: enabled('search'),
    maxResults: prefs ? prefs.maxResults : 1
  }, {queue: 1});
}

function makeRequest(name, method, url, data, callbacks) {
  log("[" + name + "]", data);
  ajax(method, url, data, function(response) {
    log("[" + name + "] response:", response);
    var result = {
      success: true,
      response: response,
      data: data
    };
    if (callbacks) {
      callbacks.forEach(function(callback) {
        callback(result);
      });
    }
  }, function(response) {
    log("[" + name + "] error:", response);
    var result = {
      success: false,
      response: response,
      data: data
    };
    if (callbacks) {
      callbacks.forEach(function(callback) {
        callback(result);
      });
    }
  });
}

// ===== Handling messages from content scripts or other extension pages

var SUPPORT = {id: 'ae5d159c-5935-4ad5-b979-ea280cb6c7ba', firstName: 'Eishay', lastName: 'Smith', name: 'Eishay Smith', pictureName: 'gmAXC.jpg', kind: 'user'};

api.port.on({
  deauthenticate: deauthenticate,
  prime_search: primeSearch,
  get_keeps: searchOnServer,
  get_keepers: function(_, respond, tab) {
    log('[get_keepers]', tab.id);
    var d = pageData[tab.nUri];
    respond({
      kept: d ? d.kept : undefined,
      keepers: d ? d.keepers : [],
      keepersTotal: d ? d.keepersTotal : 0,
      libraries: d ? d.libraries : [],
      sources: d ? d.sources : [],
      origin: webBaseUri()
    });
  },
  keep: function (data, respond, tab) {
    return;
    log('[keep]', data);
    var d = pageData[tab.nUri];
    if (!d) {
      api.tabs.emit(tab, 'kept', {fail: true});
    } else {
      var libraryId = data.libraryId || mySysLibIds[data.secret ? 1 : 0];
      ajax('POST', '/ext/libraries/' + libraryId + '/keeps', {
        url: data.url,
        canonical: !tab.usedHistoryApi && data.canonical || undefined,
        og: !tab.usedHistoryApi && data.og || undefined,
        title: !tab.usedHistoryApi && data.ogTitle || data.title,
        guided: data.guided,
        how: data.how,
        fPost: data.fPost,
        tweet: data.tweet
      }, function done(keep) {
        log('[keep:done]', keep);

        var j = d.keeps.findIndex(libraryIdIs(libraryId));
        if (j >= 0) {
          d.keeps[j] = keep;
        } else {
          d.keeps.push(keep);
        }
        respond(keep);
        updateTabsWithKeptState(tab.url, tab.nUri, d.howKept());
        notifyKifiAppTabs({type: 'keep', libraryId: libraryId, keepId: keep.id});
      }, function fail(o) {
        log('[keep:fail]', data.url, o);
        respond();
        // fix tile on active tab
        api.tabs.emit(tab, 'kept', {kept: d.howKept(), fail: true});
      });
      if (!data.libraryId) {
        // assume success for instant tile feedback on active tab
        api.tabs.emit(tab, 'kept', {
          kept: libraryId === mySysLibIds[0] ? 'public' : 'private',
          duplicate: d.keeps.some(libraryIdIs(libraryId))
        });
      }
      storeRecentLib(libraryId);
    }
    if (data.libraryId) {
      tracker.track('user_clicked_pane', {type: 'libraryChooser', action: 'kept', guided: data.guided});
    }
  },
  unkeep: function (data, respond, tab) {
    return;
    log('[unkeep]', data);
    var d = pageData[tab.nUri];
    ajax('DELETE', '/ext/libraries/' + data.libraryId + '/keeps/' + data.keepId, function done() {
      log('[unkeep:done]');
      respond(true);
      if (d) {
        d.keeps = d.keeps.filter(idIsNot(data.keepId));
        updateTabsWithKeptState(tab.url, tab.nUri, d.howKept());
      }
      notifyKifiAppTabs({type: 'unkeep', libraryId: data.libraryId, keepId: data.keepId});
    }, function fail() {
      log('[unkeep:fail]', data.keepId);
      respond();
      if (d) {
        api.tabs.emit(tab, 'kept', {kept: d.howKept() || null, fail: true});
      }
    });
    if (d && d.keeps.length === 1) {
      api.tabs.emit(tab, 'kept', {kept: null});
    }
    tracker.track('user_clicked_pane', {type: 'libraryChooser', action: 'unkept'});
  },
  update_keepscussion_recipients: function (data, respond, tab) {
    var d = pageData[tab.nUri];
    var keepId = data.keepId;
    var newUsers = data.newUsers || [];
    var newEmails = data.newEmails || [];
    var newLibraries = data.newLibraries || [];
    var removeUsers = data.removeUsers || [];
    var removeEmails = data.removeEmails || [];
    var removeLibraries = data.removeLibraries || [];

    var params = {
      libraries: {
        add: newLibraries,
        remove: removeLibraries
      },
      users: {
        add: newUsers,
        remove: removeUsers
      },
      emails: {
        add: newEmails,
        remove: removeEmails
      },
      source: api.browser.name
    };
    var removingSelf = (removeUsers.indexOf(me.id) !== -1);

    var keep = keepData[keepId];
    var activity = activityData[keepId];
    var permissions = keep && keep.viewer && keep.viewer.permissions || [];
    var requestedAdd = (newUsers.length > 0 || newLibraries.length > 0 || newEmails.length > 0);
    var requestedRemove = (removeUsers.length > 0 || removeLibraries.length > 0 || removeEmails.length > 0);
    if (keep && !removingSelf && (
      (requestedAdd && permissions.indexOf('add_participants') === -1) ||
      (requestedRemove && permissions.indexOf('remove_participants') === -1)
    )) {
      return respond(false);
    }

    updateKeepReciepients(keepId, params)
    .then(respond.bind(null, true))
    .catch(respond.bind(null, false));
  },

  keeps_and_libraries_and_organizations_and_me_and_experiments: function (_, respond, tab) {
    var d = pageData[tab.nUri];

    Promise.all([
      getKeepsByPage(tab.nUri),
      getDefaultLibraries()
    ])
    .then(function (promiseResults) {
      var keepsResponseData = promiseResults[0]; // To get the libraries of keeps on this page.
      var searchResponseData = promiseResults[1]; // The initial set of libraries in the keeper
      var libraries = keepsResponseData.keeps.reduce(function (acc, keep) {
        return acc.concat(keep.recipients.libraries);
      }, searchResponseData.results);
      libraries = unique(libraries, getId);

      var recentLibIds = loadRecentLibs();
      if (guideData) {
        var guideLibId = (guideData.library &&  guideData.library.id) || mySysLibIds[0];
        if (guideLibId) {
          var i = libraries.findIndex(idIs(guideLibId));
          if (i >= 0) {
            libraries = libraries.splice(i, 1).concat(libraries);  // list guided library first
          }
        }
      }

      libraries.filter(idIsIn(mySysLibIds)).forEach(setProp('system', true));
      libraries.filter(idIsIn(recentLibIds)).forEach(setProp('recent', true));
      var keeps = d ? d.keeps : [];

      respond({
        keeps: keeps,
        libraries: libraries,
        organizations: organizations,
        me: me,
        experiments: experiments
      });

      // preload keep details
      keeps.forEach(function (keep) {
        if (keep.libraryId) {
          ajax('GET', '/ext/libraries/' + keep.libraryId + '/keeps/' + keep.id, function (details) {
            extend(keep, details);
            // TODO: trigger get_keep response if any are waiting
          });
        }
      });
    })
    .catch(respond);
  },
  filter_libraries: function (q, respond) {
    var sf = global.scoreFilter || require('./scorefilter').scoreFilter;

    searchRecipients(q, null, null, {library: true})
    .then(function (responseData) {
      var libraries = responseData.results;
      var filterLibraries = libraries.map(function (lib) {
        lib = clone(lib);
        lib.nameParts = sf.splitOnMatches(q, lib.name);
        return lib;
      });
      respond(filterLibraries);
    })
    .catch(respond);
  },
  get_library: function (id, respond) {
    return;
    ajax('GET', '/ext/libraries/' + id, respond, respond.bind(null, null));
  },
  create_library: function (data, respond) {
    return;
    ajax('POST', '/ext/libraries', data, function (library) {
      respond(library);
      notifyKifiAppTabs({type: 'create_library', libraryId: library.id});
    }, respond.bind(null, null));
  },
  delete_library: function (libraryId, respond, tab) {
    return;
    ajax('DELETE', '/ext/libraries/' + libraryId, function () {
      respond(true);
      for (var nUri in pageData) {
        var d = pageData[nUri];
        var i = d.keeps.findIndex(libraryIdIs(libraryId));
        if (i >= 0) {
          d.keeps.splice(i, 1);
          if (nUri === tab.nUri) {
            updateTabsWithKeptState(nUri, tab.url, d.howKept());
          } else {
            updateTabsWithKeptState(nUri, d.howKept());
          }
        }
      }
      notifyKifiAppTabs({type: 'delete_library', libraryId: libraryId});
    }, respond.bind(null, false));
  },
  follow_library: function (id, respond) {
    return;
    ajax('POST', '/ext/libraries/' + id + '/join', respond.bind(null, true), respond.bind(null, false));
  },
  unfollow_library: function (id, respond) {
    return;
    ajax('POST', '/ext/libraries/' + id + '/leave', respond.bind(null, true), respond.bind(null, false));
  },
  get_keep: function (keepId, respond, tab) {
    var d = pageData[tab.nUri];
    if (d) {
      var keep = d.keeps.find(idIs(keepId));
      // TODO: wait if details (title, image, note) are not yet loaded
      respond(keep);
    }
  },
  save_keep_title: function (data, respond, tab) {
    return;
    var d = pageData[tab.nUri];
    if (d) {
      var keep = d.keeps.find(libraryIdIs(data.libraryId));
      ajax('POST', '/ext/libraries/' + keep.libraryId + '/keeps/' + keep.id, {title: data.title}, function () {
        keep.title = data.title;
        respond(true);
      }, respond.bind(null, false));
    }
  },
  save_keepscussion_title: function (data, respond, tab) {
    var d = pageData[tab.nUri];
    if (d) {
      var keep = keepData[data.keepId];
      setKeepTitle(data.keepId, data.newTitle, api.browser.name)
      .then(function () {
        keep.title = data.newTitle;
        respond(true);
      })
      .catch(respond.bind(null, false));
    }
  },
  save_keep_image: function (data, respond, tab) {
    return;
    var d = pageData[tab.nUri];
    if (d) {
      var keep = d.keeps.find(libraryIdIs(data.libraryId));
      ajax('POST', '/ext/libraries/' + keep.libraryId + '/keeps/' + keep.id + '/image', {image: data.image}, function (resp) {
        keep.image = resp.image;
        respond(true);
      }, respond.bind(null, false));
    }
  },
  save_keep_note: function (data, respond, tab) {
    return;
    var d = pageData[tab.nUri];
    if (d) {
      var keep = d.keeps.find(libraryIdIs(data.libraryId));
      ajax('POST', '/ext/libraries/' + keep.libraryId + '/keeps/' + keep.id + '/note', {note: data.note}, function () {
        keep.note = data.note;
        respond(true);
      }, respond.bind(null, false));
    }
  },
  suggest_tags: function (data, respond) {
    return;
    ajax('GET', '/api/1/keeps/suggestTags', { query: data.q, limit: data.n, keepId: data.keepId }, respond, respond.bind(null, []));
  },
  suppress_on_site: function(data, _, tab) {
    return;
    ajax('POST', '/ext/pref/keeperHidden', {url: tab.url, suppress: data});
    pageData[tab.nUri].neverOnSite = !!data;
    if (!!data) {
      api.tabs.emit(tab, 'suppressed');
    }
  },
  is_suppressed: function (data, respond, tab) {
    respond(pageData[tab.nUri].neverOnSite);
  },
  get_menu_data: function(_, respond, tab) {
    var d = pageData[tab.nUri];
    respond({
      suppressed: d && d.neverOnSite,
      packaged: api.isPackaged()
    });
  },
  silence: function (minutes) {
    if (silence) {
      api.timers.clearTimeout(silence.timeout);
    } else {
      api.tabs.each(function (tab) {
        tab.engaged = false;
        api.tabs.emit(tab, 'silence', null, {queue: 1});
      });
    }
    silence = {timeout: api.timers.setTimeout(unsilence, minutes * 60000)};
    api.tabs.eachSelected(updateIconSilence);
  },
  unsilence: unsilence.bind(null, false),
  set_keeper_pos: function(o, _, tab) {
    return;
    for (var nUri in pageData) {
      if (nUri.match(hostRe)[1] == o.host) {
        pageData[nUri].position = o.pos;
      }
    }
    ajax("POST", "/ext/pref/keeperPosition", {host: o.host, pos: o.pos});
  },
  set_look_here_mode: function (o) {
    return;
    ajax('POST', '/ext/pref/lookHereMode?on=' + o.on);
    if (prefs) prefs.lookHereMode = o.on;
    tracker.track('user_clicked_pane', {type: o.from, action: o.on ? 'toggledLookHereOn' : 'toggledLookHereOff'});
    emitAllTabs('look_here_mode', o.on);
  },
  set_enter_to_send: function(data) {
    return;
    ajax('POST', '/ext/pref/enterToSend?enterToSend=' + data);
    if (prefs) prefs.enterToSend = data;
  },
  set_max_results: function(n, respond) {
    return;
    ajax('POST', '/ext/pref/maxResults?n=' + n, respond);
    tracker.track('user_changed_setting', {category: 'search', type: 'maxResults', value: n});
    if (prefs) prefs.maxResults = n;
  },
  get_show_move_intro: function (data, respond) {
    return;
    data = data || {};
    data.domain = data.domain;
    if (data.domain) {
      ajax('GET', '/ext/pref/showExtMoveIntro', data, respond);
    } else {
      log('[get_show_move_intro] warning: client supplied empty domain');
      respond({ show: false });
    }
  },
  dont_show_ftue_again: function (data) {
    dontShowFtueAgain(data.type);
  },
  terminate_ftue: function (data) {
    var handlerName = {
      e: 'hide_ext_msg_intro',
      m: 'hide_move_keeper_intro',
      q: 'hide_quote_anywhere_ftue'
    }[data.type];

    dontShowFtueAgain(data.type);

    api.tabs.each(function (tab) {
      api.tabs.emit(tab, handlerName);
    });

    if (data.action) {
      tracker.track('user_clicked_notification', {
        category: {e: 'extMsgFTUE'}[data.type],
        action: data.action,
        subaction: data.subaction
      });
    }
  },
  track_ftue: function (type) {
    var category = {
      l: 'libFTUE',
      m: 'moveKeepFtue',
      s: 'safariInstallUpdate',
      q: 'quoteAnywhere'
    }[type];
    if (!category) return;
    tracker.track('user_was_notified', {
      category: category,
      action: 'shown'
    });
  },
  track_pane_view: function (data) {
    tracker.track('user_viewed_pane', data);
  },
  track_pane_click: function (data) {
    tracker.track('user_clicked_pane', data);
  },
  track_notified: function (data) {
    tracker.track('user_was_notified', data);
  },
  track_notification: function (data) {
    tracker.trackConsolidated('user_was_notified', extend({action: 'shown'}, data.properties), 'windows', data.id, 1200);
  },
  track_notification_click: function (data) {
    tracker.track('user_clicked_notification', data);
  },
  keeper_shown: function (data, _, tab) {
    (pageData[tab.nUri] || {}).shown = true;
    logEvent('slider', 'sliderShown', data.urls);
    if (data.action) {
      getDefaultLibraries(); // prime the cache
      tracker.track('user_expanded_keeper', {action: data.action});
    }
  },
  log_search_event: function(data) {
    return;
    ajax('search', 'POST', '/ext/search/events/' + data[0], data[1]);
  },
  import_contacts: function (data) {
    api.tabs.selectOrOpen(webBaseUri() + '/contacts/import');
    if (data) {
      tracker.track('user_clicked_pane', {
        type: data.type,
        action: 'importedGmailContacts',
        subsource: data.subsource
      });
    }
  },
  screen_capture: function (data, respond) {
    api.screenshot(function (drawableEl, canvas) {
      var bounds = data.bounds;
      var hScale = drawableEl.width / data.win.width;
      var vScale = drawableEl.height / data.win.height;
      canvas.width = bounds.width;
      canvas.height = bounds.height;
      var ctx = canvas.getContext('2d');
      for (var i = 0; i < data.rects.length; i++) {
        var rect = data.rects[i];
        if (rect.width > 0 && rect.height > 0) {
          ctx.drawImage(
            drawableEl,
            rect.left * hScale,
            rect.top * vScale,
            rect.width * hScale,
            rect.height * vScale,
            rect.left - bounds.left,
            rect.top - bounds.top,
            rect.width,
            rect.height);
        }
      }
      var dataUrl = 'data:,';
      try {
        dataUrl = canvas.toDataURL('image/png');
      } catch (e) {
        log('[screenshot] failed to render screenshot area %O', e);
      }
      respond(dataUrl);
    });
  },
  load_draft: function (data, respond, tab) {
    var drafts = loadDrafts();
    if (data.to) {
      respond(drafts[tab.nUri] || drafts[tab.url]);
    } else {
      var threadId = currentThreadId(tab);
      if (threadId) {
        respond(drafts[threadId]);
      }
    }
  },
  save_draft: function (data, _, tab) {
    var drafts = loadDrafts();
    if (data.html || data.to && data.to.length) {
      saveDraft(data.to ? tab.nUri || tab.url : currentThreadId(tab), data);
      if (data.track) {
        var d = pageData[tab.nUri || tab.url];
        var matches = data.html.match(/<a href=["']x-kifi-sel:./g) || [];
        var nR = matches.filter(endsWith('r')).length;
        var nI = matches.filter(endsWith('i')).length;
        tracker.track('user_messaged', extend({
          type: data.track.threadId ? 'draftedReply' : 'draftedConversationStarter',
          isKeep: d ? /^(?:private|public)$/.test(d.howKept()) : false,
          numLookHeres: nR + nI,
          numSelectionLookHeres: nR,
          numImageLookHeres: nI
        }, data.track));
      }
    } else {
      discardDraft(data.to ? [tab.nUri, tab.url] : [currentThreadId(tab)]);
    }
  },
  send_keepscussion: function (data, respond, tab) {
    var sentAt = Date.now();
    var draft = discardDraft([tab.nUri, tab.url]);
    var keepId;

    sendKeep({
      url: data.url,
      canonical: !tab.usedHistoryApi && data.canonical || undefined,
      og: !tab.usedHistoryApi && data.og || undefined,
      title: !tab.usedHistoryApi && data.ogTitle || data.title,
      extVersion: api.version,
      source: api.browser.name,
      eip: eip,
      note: data.text,
      users: data.users,
      emails: data.emails,
      libraries: data.libraries,
      guided: data.guided
    })
    .then(function (keepResponseData) {
      log('[send_keepscussion] send keep resp:', keepResponseData);
      keepId = keepResponseData.id;
      keepData[keepId] = keepResponseData;
      respond({ threadId: keepId });

      // Now that we've responded,
      // get the activity in the background
      return getKeepActivity(keepId);
    })
    .then(function (activityResponseData) {
      log('[send_keepscussion] get activity resp:', activityResponseData);
      activityData[keepId] = activityResponseData;
    })
    .catch(function (req) {
      log('#c00', '[send_keepscussion] resp:', req);
      var response = {status: req.status};
      var elapsedMs = Date.now() - sentAt;
      if (elapsedMs < 500) {  // allow sending progress animation to show
        api.timers.setTimeout(respond.bind(null, response), 500 - elapsedMs);
      } else {
        respond(response);
      }
      if (draft) {
        saveDraft(tab.nUri || tab.url, draft);
      }
    });
  },
  send_reply: function(data, respond) {
    var keepId = data.keepId;
    var requestData = {
      extVersion: api.version,
      source: api.browser.name,
      eip: eip,
      text: data.text
    };
    discardDraft([keepId]);

    sendKeepReply(keepId, requestData)
    .then(logAndRespond)
    .catch(logErrorAndRespond);

    function logAndRespond(o) {
      log('[send_reply] resp:', o);
      respond(o);
    }
    function logErrorAndRespond(req) {
      log('#c00', '[send_reply] resp:', req);
      respond({status: req.status});
    }
  },
  activity_event_rendered: function(o, _, tab) {
    whenTabFocused(tab, o.keepId, function (tab) {
      markRead(o.keepId, o.eventId, o.time);
      //socket.send(['set_message_read', o.eventId, o.keepId]);
    });
  },
  set_message_read: function (o, _, tab) {
    markRead(o.threadId, o.messageId, o.time);
    //socket.send(['set_message_read', o.messageId, o.threadId]);
    if (o.from === 'toggle') {
      tracker.track('user_clicked_pane', {type: trackingLocatorFor(tab.id), action: 'markedRead', category: trackingCategory(o.category)});
    } else if (o.from === 'notice') {
      tracker.track('user_clicked_pane', {type: trackingLocatorFor(tab.id), action: 'visited', category: trackingCategory(o.category)});
    }
  },
  set_message_unread: function (o, _, tab) {
    markUnread(o.threadId, o.messageId);
    //socket.send(['set_message_unread', o.messageId]);
    tracker.track('user_clicked_pane', {type: trackingLocatorFor(tab.id), action: 'markedUnread', category: trackingCategory(o.category)});
  },
  get_page_thread_count: function(_, __, tab) {
    sendPageThreadCount(tab, null, true);
  },
  activity_from: function(o, respond, tab) {
    var keepId = o.id;
    var limit = o.limit;
    var fromTime = o.fromTime;

    var cachedActivity = activityData[keepId];
    var cachedActivityEvents = cachedActivity.events;

    getKeepActivity(keepId, limit, fromTime)
    .catch(function (xhr) {
      log('[activity_from] error retrieving activity', xhr);
      emitThreadErrorToTab(keepId, xhr, tab);
    })
    .then(function (responseData) {
      var newActivity = responseData.events;
      log('[activity_from] newActivity', newActivity);
      if (newActivity) {
        activityData[keepId].events = newActivity.concat(cachedActivityEvents).filter(filterDuplicates(getId)).sort(function (a, b) {
          return b.timestamp - a.timestamp;
        });
        respond({ activity: newActivity });
      }
    })
  },
  thread: function(id, _, tab) {
    var cachedKeep = keepData[id];
    var cachedActivity = activityData[id];
    if (!cachedKeep) {
      getKeepAndActivity(id)
      .then(function (responseData) {
        var keep = keepData[id] = responseData.keep;
        var activity = activityData[id] = responseData.activity;
        doThread(id, keep, activity);
      })
      .catch(function (xhr) {
        log('[thread] error retrieving keep: ', xhr);
        emitThreadErrorToTab(id, xhr, tab);
      });
    } else {
      doThread(id, cachedKeep, cachedActivity);
    }

    function doThread(id, keep, keepActivity) {
      var th = notificationsById[id];
      if (!th) {
        //socket.send(['get_one_thread', id], function (th) {
        //   if (th) {
        //     standardizeNotification(th);
        //     updateIfJustRead(th);
        //     notificationsById[th.thread] = th;
        //   }
        // });
      }

      var msgs = messageData[id];
      if (msgs) {
        emitThreadToTab(id, keep, keepActivity, tab);
      } else {
        //socket.send(['get_thread', id]);
      }
    }
  },
  thread_list: function(o, _, tab) {
    var uri = tab.nUri || tab.url;
    var tl = notificationLists[o.kind === 'page' ? uri : o.kind];
    if (tl) {
      if (o.kind === 'unread') { // detect, report, recover from unread threadlist constistency issues
        if (tl.ids.map(idToThread).filter(isUnread).length < tl.ids.length) {
          getLatestThreads();
          api.errors.push({error: Error('Read threads found in notificationLists.unread'), params: {
            threads: tl.ids.map(idToThread).map(function (th) {
              return {thread: th.thread, id: th.id, time: th.time, unread: th.unread, readAt: threadReadAt[th.thread]};
            })
          }});
          return;
        } else if (tl.ids.length === 0 && tl.numTotal > 0) {
          //socket.send(['get_unread_threads', THREAD_BATCH_SIZE], gotFilteredThreads.bind(null, 'unread', tl));
          api.errors.push({error: Error('No unread threads available to show'), params: {threadList: tl}});
          return;
        }
      }
      emitThreadsToTab(o.kind, tl, tab);
      if (o.kind === 'page') {  // prefetch
        tl.ids.forEach(function (id) {
          if (!messageData[id]) {
            //socket.send(['get_thread', id]);
          }
        });
      }
    } else {
      // TODO: remember that this tab needs the kind threadlist until it gets it or its pane changes?
    }
    if (o.first) {
      sendUnreadThreadCount(tab);
      sendPageThreadCount(tab, null, true);
    }
  },
  get_older_threads: function(o, respond, tab) {
    var list = notificationLists[o.kind === 'page' ? tab.nUri : o.kind];
    var n = list ? list.ids.length : 0;
    for (var i = n - 1; i >= 0 && notificationsById[list.ids[i]].time < o.time; i--);
    if (++i < n || list && list.includesOldest) {
      var threads = list.ids.slice(i, i + THREAD_BATCH_SIZE).map(idToThread);
      respond({
        threads: threads,
        includesOldest: list.includesOldest && i + threads.length === n
      });
    } else {
      var socketMessage = {
        all: ['get_threads_before'],
        unread: ['get_unread_threads_before'],
        sent: ['get_sent_threads_before'],
        page: ['get_page_threads_before', tab.nUri]
      }[o.kind];
      socketMessage.push(THREAD_BATCH_SIZE, o.time);
      //socket.send(socketMessage, function (arr) {
      //   arr.forEach(function (th) {
      //     standardizeNotification(th);
      //     updateIfJustRead(th);
      //     notificationsById[th.thread] = th;
      //   });
      //   var includesOldest = arr.length < THREAD_BATCH_SIZE;
      //   var list = notificationLists[o.kind === 'page' ? tab.nUri : o.kind];
      //   if (list && list.ids[list.ids.length - 1] === o.threadId) {
      //     list.insertOlder(arr.map(getThreadId));
      //     list.includesOldest = includesOldest;
      //   }
      //   // TODO: may also want to append/update sent & unread if this is the all kind
      //   respond({threads: arr, includesOldest: includesOldest});
      // });
    }
  },
  'pane?': function (_, respond) {
    respond(getDefaultPaneLocator());
  },
  pane: function(o, _, tab) {
    if (o.old) {
      var arr = tabsByLocator[o.old];
      if (arr) {
        arr = arr.filter(idIsNot(tab.id));
        if (arr.length) {
          tabsByLocator[o.old] = arr;
        } else {
          delete tabsByLocator[o.old];
        }
      }
    }
    var loc = o.new;
    if (loc) {
      var arr = tabsByLocator[loc];
      if (arr) {
        arr = arr.filter(idIsNot(tab.id));
        arr.push(tab);
      }
      tabsByLocator[loc] = arr || [tab];
      tracker.track('user_viewed_pane', {type: trackingLocator(loc), subsource: o.how});
      if (loc === '/messages:unread') {
        store('unread', true);
      } else if (loc === '/messages:all') {
        unstore('unread');
      }
    }
  },
  set_all_threads_read: function (msgId) {
    // not updating local cache until server responds due to bulk nature of action
    if (!msgId) {
      var threadId = notificationLists.all && notificationLists.all.ids[0];
      msgId = threadId && notificationsById[threadId].id;
    }
    if (msgId) {
      //socket.send(['set_all_notifications_visited', msgId]);
    }
  },
  load_image: function (path, respond, tab) {
    // The Firefox worker XHR dislikes protocol-less URLs, so prefix with the tab's protocol.
    path = (path.indexOf('//') === 0 ? tab.url.slice(0, tab.url.indexOf(':') + 1) + path : path);
    var imageType = path.slice(path.lastIndexOf('.') + 1);
    var xhr = new api.xhr();
    xhr.open('GET', path, true);
    xhr.responseType = 'arraybuffer';

    xhr.onload = function (e) {
      if (this.status === 200 || this.status === 0) {
        respond({ uri: 'data:image/' + imageType + ';base64,' + arrayBufferToBase64(this.response) });
      } else {
        respond({ error: 'Status ' + this.status + ' did not equal 200 for ' + path });
      }
    };
    xhr.onerror = respond.bind(null, { error: 'Couldn\'t load image ' + path });
    xhr.send();
  },
  me: function(_, respond) {
    respond(me);
  },
  prefs: function(_, respond) {
    respond(prefs);
  },
  settings: function(_, __, tab) {
    emitSettings(tab);
  },
  browser: function (_, respond) {
    respond(api.browser);
  },
  save_setting: function(o, respond, tab) {
    return;
    if (o.name === 'emails') {
      ajax('POST', '/ext/pref/email/message/' + o.value, function () {
        if (prefs) {
          prefs.messagingEmails = o.value;
        }
        onSettingCommitted();
      });
    } else if (o.name === 'social') {
      ajax('POST', '/ext/pref/hideSocialTooltip?hideTooltips=' + !o.value, function () {
        if (prefs) {
          prefs.hideSocialTooltip = !o.value;
        }
        onSettingCommitted();
      });
    } else {
      store('_' + o.name, o.value ? 'y' : 'n');
      onSettingCommitted();
      if (o.name === 'keeper') {
        api.tabs.each(function (tab) {
          var d = tab.nUri && pageData[tab.nUri];
          if (d && !d.neverOnSite) {
            api.tabs.emit(tab, 'show_keeper', o.value);
          }
        });
      }
    }
    tracker.track('user_changed_setting', {
      category:
        ~['sounds','popups','emails','social'].indexOf(o.name) ? 'notification' :
        'keeper' === o.name ? 'keeper' :
        'search' === o.name ? 'search' : 'unknown',
      type: 'search' === o.name ? 'inGoogle' : o.name,
      value: o.value ? 'on' : 'off'
    });
    function onSettingCommitted() {
      respond();
      forEachTabAtLocator('/settings', function (tab2) {
        if (tab2 !== tab) {
          emitSettings(tab2);
        }
      });
    }
  },
  play_alert: function() {
    playNotificationSound();
  },
  auth_info: function(_, respond) {
    var dev = api.mode.isDev();
    respond({
      origin: webBaseUri(),
      data: {
        facebook: dev ? 530357056981814 : 104629159695560,
        linkedin: dev ? 'ovlhms1y0fjr' : 'r11loldy9zlg'
      }});
  },
  search_recipients: function (data, respond, tab) {
    data = data || {};
    data.includeSelf = (typeof data.includeSelf !== 'undefined' ? data.includeSelf : true);

    searchRecipients(data.q, data.n, data.offset, data.searchFor)
    .then(function (responseData) {
      var recipients = responseData.results;
      var results = toResults(recipients, data.q, me, data.n, data.exclude, data.includeSelf);
      respond(results);
    })
    .catch(respond.bind(null, null));
  },
  delete_contact: function (email, respond) {
    return;
    ajax('POST', '/ext/contacts/hide', {email: email}, function (status) {
      log('[delete_contact] resp:', status);
      contactSearchCache = null;
      respond(true);
    }, function () {
      log('#c00', '[delete_contact] resp:', status);
      contactSearchCache = null;
      respond(false);
    });
  },
  open_tab: function (data) {
    var url = webBaseUri() + data.path;
    var existingTab = api.tabs.anyAt(url);
    if (existingTab && existingTab.id) {
      api.tabs.select(existingTab.id);
    } else {
      api.tabs.open(url, function () {});
    }
    if (data.source === 'keeper') {
      tracker.track('user_clicked_pane', {type: 'keeper', action: 'visitKifiSite'});
    }
  },
  close_tab: function (_, __, tab) {
    api.tabs.close(tab.id);
  },
  open_deep_link: function(link, _, tab) {
    var tabIdWithLink;
    if (link.inThisTab || verySimilarUrls(tab.nUri, link.nUri) || verySimilarUrls(tab.url, link.nUri)) {
      tabIdWithLink = tab.id;
      awaitDeepLink(link, tab.id);
      trackClick();
    } else {
      var tabs = tabsByUrl[link.nUri];
      var exactUrls = [];
      var similarUrls = [];
      api.tabs.each(function (page) {
        if ((page.url && link.nUri === page.url) || (page.nUri && link.nUri === page.nUri)) {
          exactUrls.push(page);
        } else if (verySimilarUrls(link.nUri, page.url) || verySimilarUrls(link.nUri, page.nUri)) {
          similarUrls.push(page);
        }
      });

      var tabs = tabsByUrl[link.nUri];
      var existingTab = tabs ? tabs[0] : (exactUrls[0] || similarUrls[0]);
      if (existingTab && existingTab.id) {  // page's normalized URI may have changed
        tabIdWithLink = existingTab.id;
        awaitDeepLink(link, existingTab.id);
        api.tabs.select(existingTab.id);
        trackClick();
      } else {
        api.tabs.open(link.nUri, function (tabId) {
          tabIdWithLink = tabId;
          awaitDeepLink(link, tabId);
          trackClick();
        });
      }
    }
    function trackClick() {
      if (link.from === 'notice' && tabIdWithLink) {
        tracker.track('user_clicked_pane', {type: trackingLocatorFor(tabIdWithLink), action: 'view', category: 'message'});
      }
    }
  },
  open_support_chat: function (_, __, tab) {
    api.tabs.emit(tab, 'compose', {to: SUPPORT, trigger: 'deepLink'}, {queue: 1});
  },
  logged_in: authenticate.bind(null, api.noop),
  remove_notification: function (threadId) {
    removeNotificationPopups(threadId);
  },
  await_deep_link: function(link, _, tab) {
    awaitDeepLink(link, tab.id);
    if (guideData && /^#guide\/\d/.test(link.locator)) {
      var step = +link.locator.substr(7, 1);
      switch (step) {
        case 1:
          pageData[link.url] = new PageData({shown: true});
          tabsByUrl[link.url] = tabsByUrl[link.url] || [];
          break;
        case 2:
          var keep = guideData.keep;
          var query = keep.query.replace(/\+/g, ' ');
          var entry = searchPrefetchCache[query] = {
            response: pimpSearchResponse([{
              context: 'guide',
              uuid: '00000000-0000-0000-0000-000000000000',
              query: query,
              hits: [{
                keepId: '00000000-0000-0000-0000-000000000000',
                title: keep.title,
                url: keep.url,
                matches: keep.matches
              }],
              cutPoint: 1
            }, {
              hits: [{
                keepers: [-1],
                keepersTotal: 816,
                libraries: [0, -1]
              }],
              libraries: [guideData.library || {id: mySysLibIds[0]}],
              users: []
            }])
          };
          entry.expireTimeout = api.timers.setTimeout(cullPrefetchedResults.bind(null, query, entry), 10000);
          break;
      }
    }
  },
  add_participants: function(data) {
    //socket.send(['add_participants_to_thread', data.threadId, data.ids.map(makeObjectsForEmailAddresses)]);
  },
  is_muted: function(threadId, respond) {
    var th = notificationsById[threadId];
    respond({
      success: Boolean(th),
      response: Boolean(th && th.muted)
    });
  },
  mute_thread: function(threadId) {
    //socket.send(['mute_thread', threadId]);
    setMuted(threadId, true);
  },
  unmute_thread: function(threadId) {
    //socket.send(['unmute_thread', threadId]);
    setMuted(threadId, false);
  },
  count_bookmarks: function(_, respond) {
    api.bookmarks.getAll(function (bms) {
      respond(bms.length);
    });
  },
  get_bookmark_count_if_should_import: function(_, respond) {  // TODO: remove (obsolete)
    if (stored('prompt_to_import_bookmarks')) {
      api.bookmarks.getAll(function (bms) {
        respond(bms.length);
      });
    }
  },
  import_bookmarks: function (libraryId) {
    unstore('prompt_to_import_bookmarks');
    postBookmarks(api.bookmarks.getAll, 'INIT_LOAD', libraryId);
  },
  import_bookmarks_declined: function() {
    unstore('prompt_to_import_bookmarks')
  },
  toggle_mode: function () {
    if (!api.isPackaged()) {
      api.mode.toggle();
    }
  },
  start_guide: function (_, __, tab) {
    return;
    ajax('GET', '/ext/guide', function (data) {
      guideData = data;
      data.keep.image.width /= 2;
      data.keep.image.height /= 2;
      api.tabs.emit(tab, 'guide', {step: 0, page: data.keep});
    });
    unsilence(false);
  },
  track_guide: function (stepParts) {
    tracker.track('user_viewed_pane', {type: 'guide' + stepParts.join('')});
  },
  track_guide_choice: function () {
    tracker.track('user_clicked_pane', {type: 'guide01', action: 'chooseExamplePage', subaction: guideData.keep.track});
  },
  resume_guide: function (step, _, tab) {
    return;
    if (guideData) {
      resume(guideData);
    } else {
      ajax('GET', '/ext/guide', resume);
    }
    function resume(data) {
      guideData = data;
      var page = guideData.keep;
      if (step === 1) {
        page = extend({libraryId: (guideData.library || {id: mySysLibIds[0]}).id}, page);
      }
      api.tabs.emit(tab, 'guide', {step: step, page: page});
    }
  },
  end_guide: function (stepParts) {
    tracker.track('user_clicked_pane', {type: 'guide' + stepParts.join(''), action: 'closeGuide'});
    if (api.isPackaged()) {
      guideData = null;
    }
  },
  'api:safari-update-seen': function () {
    api.tabs.each(function (page) {
      api.tabs.emit(page, 'api:safari-update-clear');
    });
  }
});

function unsilence(tab) {
  if (silence) {
    api.timers.clearTimeout(silence.timeout);
    silence = null;
    api.tabs.eachSelected(kifify);
    if (tab || tab !== false && (tab = api.tabs.getFocused())) {
      api.tabs.emit(tab, 'unsilenced');
    }
  }
}

function standardizeUser(u, o) {
  u.name = (u.firstName + ' ' + u.lastName).trim();
  u.orgs = o;
  return u;
}

function removeNotificationPopups(threadId) {
  emitAllTabs('remove_notification', threadId);
}

function standardizeNotification(n) {
  n.category = (n.category || 'message').toLowerCase();
  n.unread = n.unread || (n.unreadAuthors > 0);
  if (n.time[n.time.length - 1] !== 'Z') {
    n.time = new Date(n.time).toISOString();
  }
  return n;
}

function handleRealTimeNotification(n) {
  if (n.unread && !n.muted && !silence) {
    if (enabled('sounds')) {
      playNotificationSound();
    }
    if (enabled('popups')) {
      api.tabs.eachSelected(function (tab) {
        api.tabs.emit(tab, 'show_notification', n, {queue: true});
      });
    }
  }
}

function playNotificationSound() {
  api.play('media/notification.mp3');
}

function insertNewNotification(n) {
  var n0 = notificationsById[n.thread];
  // proceed only if we don't already have this notification or a newer one for the same thread
  if (!n0 || n0.id !== n.id && n0.time < n.time) {
    notificationsById[n.thread] = n;
    updateIfJustRead(n);
    var o = {all: true, page: true, unread: n.unread, sent: isSent(n)};
    for (var kind in o) {
      if (o[kind]) {
        var tl = notificationLists[kind === 'page' ? n.url : kind];
        if (tl && tl.insertOrReplace(n0, n, log) && kind === 'page') {
          forEachTabAt(n.url, function (tab) {
            sendPageThreadCount(tab, tl);
          });
        }
      }
    }
    var unreadCountChanged = n0 ? n0.unread !== n.unread : n.unread;
    forEachTabAtThreadList(function (tab) {
      var thisPage = n.url === tab.nUri || n.url === tab.url;
      api.tabs.emit(tab, 'new_thread', {thread: n, thisPage: thisPage}, {queue: true});
      if (unreadCountChanged) {
        sendUnreadThreadCount(tab);
      }
    });
    return true;
  }
}

function removeNotification(threadId) {
  var n0 = notificationsById[threadId];

  if (n0) {
    markRead(threadId, n0.id, +new Date());
    removeNotificationPopups(threadId);

    ['all', 'page', 'unread', 'sent'].map(function (kind) {
      var tl = notificationLists[kind];
      if (tl && tl.remove(threadId, log) && kind === 'page') {
        forEachTabAt(n0.url, function (tab) {
          sendPageThreadCount(tab, tl);
        });
      }
    });
  }

  delete notificationsById[threadId];
  delete threadReadAt[threadId];
  delete keepData[threadId];

  tellVisibleTabsNoticeCountIfChanged();
}

function updateIfJustRead(th) {
  if (th.unread && threadReadAt[th.thread] >= th.time) {
    th.unread = false;
    th.unreadAuthors = th.unreadMessages = 0;
  }
}

// messageId is of last read message
function markUnread(threadId, messageId) {
  delete threadReadAt[threadId];
  var th = notificationsById[threadId];
  if (th && !th.unread) {
    var thOld = clone(th);
    th.unread = true;
    th.unreadAuthors = th.unreadMessages = 1;
    (function insertIntoUnread(tl) {
      if (tl && tl.includesAllSince(th)) {
        tl.insertOrReplace(thOld, th, log);
      } else if (tl) {
        tl.numTotal++;
      }
    }(notificationLists.unread));
    if (!th.muted) {
      var tlKeys = ['all', th.url];
      if (isSent(th)) {
        tlKeys.push('sent');
      }
      tlKeys.forEach(function (key) {
        var tl = notificationLists[key];
        if (tl) {
          tl.incNumUnreadUnmuted();
        }
      });
    }

    forEachTabAtThreadList(function (tab) {
      api.tabs.emit(tab, 'thread_unread', th);
      sendUnreadThreadCount(tab);
    });

    tellVisibleTabsNoticeCountIfChanged();
    return true;
  }
}

// messageId is of last read message, time is its createdAt time.
function markRead(threadId, messageId, time) {
  if (!(threadReadAt[threadId] >= time)) {
    threadReadAt[threadId] = time;
  }
  var th = notificationsById[threadId];
  if (th && th.unread && (th.id === messageId || th.time <= time)) {
    th.unread = false;
    th.unreadAuthors = th.unreadMessages = 0;
    (function removeFromUnread(tl) {
      if (!tl) return;
      var numRemoved = tl.remove(th.thread, log);
      if (!tl.includesOldest) {
        if (numRemoved === 0 && tl.numTotal > 0 && !tl.includesAllSince(th)) {
          tl.numTotal--;
        }
        if (tl.numTotal === tl.ids.length) {
          tl.includesOldest = true;
        } else if (tl.ids.length <= 1) {
          //socket.send(['get_unread_threads', THREAD_BATCH_SIZE], gotFilteredThreads.bind(null, 'unread', tl));
        }
      }
    }(notificationLists.unread));
    if (!th.muted) {
      var tlKeys = ['all', 'unread', th.url];
      if (isSent(th)) {
        tlKeys.push('sent');
      }
      tlKeys.forEach(function (key) {
        var tl = notificationLists[key];
        if (tl) {
          tl.decNumUnreadUnmuted(log);
        }
      });
    }

    forEachTabAtThreadList(function (tab) {
      api.tabs.emit(tab, 'thread_read', {
        time: time,
        threadId: threadId,
        id: messageId});
      sendUnreadThreadCount(tab);
    });

    tellVisibleTabsNoticeCountIfChanged();
    return true;
  } else {
    log('#c00', '[markRead] noop', threadId, messageId, time,
      th ? '' : 'not loaded',
      th && !th.unread ? 'read' : '',
      th && th.id !== messageId ? 'message: ' + th.id : '',
      th && th.time > time ? 'newer: ' + th.time : '');
  }
}

function markAllThreadsRead(messageId, time) {  // .id and .time of most recent thread to mark
  var timeDate = new Date(time);
  for (var id in notificationsById) {
    var th = notificationsById[id];
    if (th.unread && (th.id === messageId || th.time <= time)) {
      th.unread = false;
      th.unreadAuthors = th.unreadMessages = 0;
      if (timeDate - new Date(th.time) < 180000) {
        removeNotificationPopups(id);
      }
    }
  }

  var tlUnread = notificationLists.unread;
  if (tlUnread) {
    for (var i = tlUnread.ids.length; i--;) {
      var id = tlUnread.ids[i];
      if (!notificationsById[id].unread) {
        tlUnread.ids.splice(i, 1);
      }
    }
    tlUnread.numTotal = tlUnread.ids.length;  // any not loaded are older and now marked read
  }
  var tlAll = notificationLists.all;
  if (tlAll) {
    tlAll.numUnreadUnmuted = tlAll.countUnreadUnmuted();
  }

  forEachTabAtThreadList(function (tab) {
    api.tabs.emit(tab, 'all_threads_read', {id: messageId, time: time});
    sendUnreadThreadCount(tab);
  });

  tellVisibleTabsNoticeCountIfChanged();
}

function setMuted(threadId, muted) {
  var thread = notificationsById[threadId];
  if (thread && thread.muted !== muted) {
    thread.muted = muted;
    if (thread.unread) {
      var tlKeys = ['all', thread.url];
      if (isSent(thread)) {
        tlKeys.push('sent');
      }
      tlKeys.forEach(function (key) {
        var tl = notificationLists[key];
        if (tl) {
          tl[muted ? 'decNumUnreadUnmuted' : 'incNumUnreadUnmuted'](log);
        }
      });
      tellVisibleTabsNoticeCountIfChanged();
    }
    forEachTabAtLocator('/messages/' + threadId, function (tab) {
      api.tabs.emit(tab, 'muted', {threadId: threadId, muted: muted});
    });
  }
}

function getDefaultPaneLocator() {
  return stored('unread') ? '/messages:unread' : '/messages:all';
}

function sendUnreadThreadCount(tab) {
  var tl = notificationLists.unread;
  if (tl) {
    api.tabs.emit(tab, 'unread_thread_count', tl.numTotal, {queue: 1});
  } // else will be pushed to tab when known
}

function sendPageThreadCount(tab, tl, load) {
  var uri = tab.nUri || tab.url;
  tl = tl || notificationLists[uri];
  if (tl) {
    api.tabs.emit(tab, 'page_thread_count', {count: tl.numTotal, id: tl.numTotal === 1 ? tl.ids[0] : undefined}, {queue: 1});
  } else if (load) {
    //socket.send(['get_page_threads', tab.url, THREAD_BATCH_SIZE], gotPageThreads.bind(null, uri));
  } // will be pushed to tab when known
}

function awaitDeepLink(link, tabId, retrySec) {
  var loc = link.locator;
  if (loc) {
    api.timers.clearTimeout(timeouts[tabId]);
    delete timeouts[tabId];
    var tab = api.tabs.get(tabId);
    var linkUrl = link.url || link.nUri;
    var nTabIsSame = tab && tab.nUri && sameOrSimilarBaseDomain(linkUrl, tab.nUri);
    var uTabIsSame = tab && tab.url && sameOrSimilarBaseDomain(linkUrl, tab.url);

    if (tab && (nTabIsSame || uTabIsSame)) {
      log('[awaitDeepLink]', tabId, link);
      if (loc.lastIndexOf('#guide/', 0) === 0) {
        var step = +loc.substr(7, 1);
        var page = guideData.keep;
        if (step === 1) {
          page = extend({libraryId: (guideData.library || {id: mySysLibIds[0]}).id}, page);
        }
        api.tabs.emit(tab, 'guide', {step: step, page: page}, {queue: 1});
      } else if (loc.indexOf('#compose') >= 0) {
        api.tabs.emit(tab, 'compose', {trigger: 'deepLink'}, {queue: 1});
      } else {
        var linkUrl = link.url || link.nUri;
        api.tabs.emit(tab, 'show_pane', {
          trigger: 'deepLink',
          locator: loc,
          redirected: linkUrl !== (tab.nUri || tab.url) && linkUrl !== (tab.url || tab.nUri)
        }, {queue: 1});
      }
    } else if ((retrySec = retrySec || .5) < 5) {
      log('[awaitDeepLink]', tabId, 'retrying in', retrySec, 'sec');
      timeouts[tabId] = api.timers.setTimeout(awaitDeepLink.bind(null, link, tabId, retrySec + .5), retrySec * 1000);
    }
    if (loc.lastIndexOf('/messages/', 0) === 0) {
      var threadId = loc.substr(10);
      if (!messageData[threadId]) {
        //socket.send(['get_thread', threadId]);  // a head start
      }
    }
  } else {
    log('[awaitDeepLink] no locator', tabId, link);
  }
}

function notifyKifiAppTabs(data) {
  var prefix = webBaseUri();
  for (var url in tabsByUrl) {
    if (url.lastIndexOf(prefix, 0) === 0) {
      tabsByUrl[url].forEach(function (tab) {
        api.tabs.emit(tab, 'post_message', data);
      });
    }
  }
}

function forEachTabAt() { // (url[, url]..., f)
  var done = {};
  var i = arguments.length - 1;
  var f = arguments[i];
  while (--i >= 0) {
    var url = arguments[i];
    if (!done[url]) {
      done[url] = true;
      var tabs = tabsByUrl[url];
      if (tabs) {
        tabs.forEach(f);
      }
    }
  }
}

function forEachTabAtLocator(loc, f) {
  var tabs = tabsByLocator[loc];
  if (tabs) {
    tabs.forEach(f);
  }
}

var threadListLocatorRe = /^\/messages(:[a-z]+)?$/;
function forEachTabAtThreadList(f) {
  for (var loc in tabsByLocator) {
    if (threadListLocatorRe.test(loc)) {
      tabsByLocator[loc].forEach(f);
    }
  }
}

var threadLocatorRe = /^\/messages\/[A-Za-z0-9-]+$/;
function forEachThreadOpenInPane(f) {
  for (var loc in tabsByLocator) {
    if (threadLocatorRe.test(loc)) {
      f(loc.substr(10));
    }
  }
}
function currentThreadId(tab) {
  for (var loc in tabsByLocator) {
    if (threadLocatorRe.test(loc) && ~tabsByLocator[loc].indexOf(tab)) {
      return loc.substr(10);
    }
  }
}

function forEachTabAtUriAndLocator() { // (url[, url]..., loc, f)
  var done = {};
  var f = arguments[arguments.length - 1];
  var loc = arguments[arguments.length - 2];
  for (var i = arguments.length - 2; i--;) {
    var url = arguments[i];
    if (!done[url]) {
      done[url] = true;
      var arr1, arr2;
      if ((arr1 = tabsByUrl[url]) && (arr2 = tabsByLocator[loc])) {
        for (var j = arr1.length; j--;) {
          var tab = arr1[j];
          if (~arr2.indexOf(tab)) {
            f(tab);
          }
        }
      }
    }
  }
}

function tellVisibleTabsNoticeCountIfChanged() {
  if (!notificationLists.all) return;
  api.tabs.eachSelected(function (tab) {
    if (tab.count !== notificationLists.all.numUnreadUnmuted) {
      tab.count = notificationLists.all.numUnreadUnmuted;
      api.tabs.emit(tab, 'count', tab.count, {queue: 1});
    }
  });
}

function searchOnServer(request, respond) {
  if (request.first && getPrefetchedResults(request.query, respond)) return;

  if (!me || !enabled('search')) {
    log('[searchOnServer] noop, me:', me);
    respond({});
    return;
  }

  var params = {
    q: request.query,
    f: request.filter && (request.filter.who !== 'a' ? request.filter.who : null), // f=a disables tail cutting
    n: 5,
    u: request.lastUUID,
    c: request.context,
    v: api.version,
    w: request.whence};

  var o1, o1Time, o1Len;
  var o2, o2Time;
  function tryParseChunks(text) {
    var i = 1;
    while (!o1 && i > 0) {
      i = text.indexOf('}{"', i) + 1;
      if (i > 0) {
        try {
          o1 = JSON.parse(text.substr(0, i));
          o1Time = Date.now();
          o1Len = i;
          o2 = JSON.parse(text.substr(i));
          o2Time = o1Time;
        } catch (x) {}
      }
    }
  }

  ajax('search', 'GET', '/ext/search', params, function (text) {
    return;
    if (!o1) {
      tryParseChunks(text);
    } else if (!o2) {
      o2 = JSON.parse(text.substr(o1Len));
      o2Time = Date.now();
    }
    o1.chunkDelta = o2Time - o1Time;
    log('[searchOnServer] %i hits, cutPoint: %i, chunkDelta: %ims', o1.hits.length, o1.cutPoint, o1.chunkDelta);
    respond(pimpSearchResponse([o1, o2], request.filter, o1.hits.length < params.n && (params.c || params.f)));
  }, null, function progress(text) {
    var len = text.length;
    log('[search:progress]', len);
    if (!o1 && text.indexOf('}', len - 1) > 0) {
      tryParseChunks(text);
      if (!o1) {
        try {
          o1 = JSON.parse(text);
          o1Time = Date.now();
          o1Len = len;
        } catch (x) {}
      }
    }
  });
  return true;
}

function pimpSearchResponse(resp, filter, noMore) {
  var o = resp[0];
  o.filter = filter;
  o.me = me;
  o.prefs = prefs || {maxResults: 1};
  o.origin = webBaseUri();
  o.experiments = experiments;
  o.admBaseUri = admBaseUri();
  o.myTotal = o.myTotal || 0;
  o.friendsTotal = o.friendsTotal || 0;
  if (noMore) {
    o.mayHaveMore = false;
  }
  o.hits.forEach(pimpSearchHit.bind(null, resp[1].hits, resp[1].libraries));
  o.users = resp[1].users;
  o.libraries = resp[1].libraries;
  return o;
}

function pimpSearchHit(hits, libs, hit, i) {
  extend(hit, hits[i]);

  // remove my system libraries
  var hitLibs = hit.libraries;
  if (hitLibs) {
    for (var j = 0; j < hitLibs.length;) {
      var k = hitLibs[j];
      if (mySysLibIds.indexOf(libs[k].id) >= 0) {
        hitLibs.splice(j, 2);
      } else {
        j += 2;
      }
    }
  }
}

function kifify(tab) {
  log('[kifify]', tab.id, tab.url, tab.icon || '', tab.nUri || '', me ? '' : 'no session');
  if (!tab.icon) {
    api.icon.set(tab, 'icons/url_gray' + (silence ? '_II' : '') + '.png');
  } else {
    updateIconSilence(tab);
  }

  if (!me) {
    return;
    if (!stored('logout') || tab.url.indexOf(webBaseUri()) === 0) {
      ajax('GET', '/ext/auth', function (loggedIn) {
        if (loggedIn !== false) {
          authenticate(function() {
            if (api.tabs.get(tab.id) === tab) {  // tab still at same page
              kifify(tab);
            }
          });
        }
      });
    }
    return;
  }

  if (notificationLists.all && tab.count !== notificationLists.all.numUnreadUnmuted) {
    tab.count = notificationLists.all.numUnreadUnmuted;
    api.tabs.emit(tab, 'count', tab.count, {queue: 1});
  }

  var url = tab.url;
  var uri = tab.nUri || url;

  // page data
  var d = pageData[uri];
  if (d) {
    if (!tab.nUri) {
      stashTabByNormUri(tab, uri);
    }
    kififyWithPageData(tab, d);
  } else {
    return;
    ajax('POST', '/ext/page', {url: url}, gotPageDetailsFor.bind(null, url, tab), function fail(xhr) {
      if (xhr.status === 403) {
        clearSession();
      }
    });
  }
}

function stashTabByNormUri(tab, uri) {
  tab.nUri = uri;
  var tabs = tabsByUrl[uri];
  if (tabs) {
    for (var i = tabs.length; i--;) {
      if (tabs[i].id === tab.id) {
        tabs.splice(i, 1);
      }
    }
    tabs.push(tab);
  } else {
    tabsByUrl[uri] = [tab];
  }
  log('[stashTabByNormUri]', tab.id);
}

function kififyWithPageData(tab, d) {
  log('[kififyWithPageData]', tab.id, tab.engaged ? 'already engaged' : '');
  setIcon(d.howKept(), tab);
  if (silence) return;

  var hide = d.neverOnSite || !enabled('keeper');
  api.tabs.emit(tab, 'init', {  // harmless if sent to same page more than once
    kept: d.howKept(),
    position: d.position,
    hide: hide
  }, {queue: 1});

  var hideSocialTooltip = prefs && prefs.hideSocialTooltip;
  // consider triggering automatic keeper behavior on page to engage user (only once)
  if (!tab.engaged) {
    tab.engaged = true;
    if (!d.kept && !hide) {
      if (urlPatterns && urlPatterns.some(reTest(tab.url))) {
        log('[initTab]', tab.id, 'restricted');
      } else if (d.shown) {
        log('[initTab]', tab.id, 'shown before');
      } else if (!hideSocialTooltip && (d.keepers.length || d.libraries.length || d.sources.length)) {
        tab.keepersSec = d.sources.filter(isSlack).length ? 0 : 20;
        if (api.tabs.isFocused(tab)) {
          scheduleAutoEngage(tab, 'keepers');
        }
      }
    }
  }
}
function isSlack(o) { return o.slack; }

function gotPageDetailsFor(url, tab, resp) {
  var tabIsOld = api.tabs.get(tab.id) !== tab || url.split('#', 1)[0] !== tab.url.split('#', 1)[0];

  log('[gotPageDetailsFor]', tab.id, tabIsOld ? 'OLD' : '', url);

  var nUri = resp.normalized;
  var d = pageData[nUri];
  if (d) {
    d.update(resp);
  }

  if (!tabIsOld) {
    if (!d) {
      pageData[nUri] = d = new PageData(resp);
    }
    stashTabByNormUri(tab, nUri);
    kififyWithPageData(tab, d);
  }
}

function gotPageThreads(uri, nUri, threads, numTotal) {
  log('[gotPageThreads]', threads.length, 'of', numTotal, uri, nUri !== uri ? nUri : '');

  // incorporating new threads into our cache and noting any changes
  var updatedThreadIds = [];
  threads.forEach(function (th) {
    standardizeNotification(th);
    var oldTh = notificationsById[th.thread];
    if (!oldTh || oldTh.time <= th.time) {
      updateIfJustRead(th);
      if (oldTh && oldTh.unread && !th.unread) {
        markRead(th.thread, th.id, th.time);
      }
      notificationsById[th.thread] = th;
      if (oldTh) {
        updatedThreadIds.push(th.thread);
      }
    }
  });

  // reusing (sharing) the page ThreadList of an earlier normalization of the URL if possible
  var pt = notificationLists[nUri] || notificationLists[threads.length ? threads[0].url : ''];
  if (pt) {
    pt.ids = threads.map(getThreadId);
    pt.numTotal = numTotal;
  } else {
    pt = new ThreadList(notificationsById, threads.map(getThreadId), numTotal, null);
  }
  pt.includesOldest = threads.length < THREAD_BATCH_SIZE;
  notificationLists[nUri] = pt;

  // sending new page threads and count to any tabs on this page with pane open to page threads
  forEachTabAtUriAndLocator(uri, nUri, '/messages', emitThreadsToTab.bind(null, 'page', pt));
  forEachTabAt(uri, nUri, function (tab) {
    sendPageThreadCount(tab, pt); // TODO: only if pane is open
  });

  // updating tabs currently displaying any updated threads
  var requested = {};
  updatedThreadIds.forEach(function (threadId) {
    //socket.send(['get_thread', threadId]);
    requested[threadId] = true;
  });

  // prefetch any unread threads
  pt.forEachUnread(function (threadId) {
    if (!messageData[threadId] && !requested[threadId]) {
      //socket.send(['get_thread', threadId]);
      requested[threadId] = true;
    }
  });
}

function isSent(th) {
  return th.firstAuthor != null && th.participants[th.firstAuthor] && th.participants[th.firstAuthor].id === me.id;
}

function isUnread(th) {
  return th.unread;
}

function paneLocatorFor(tabId) {
  var hasThisTabId = idIs(tabId);
  for (var loc in tabsByLocator) {
    if (tabsByLocator[loc].some(hasThisTabId)) {
      return loc;
    }
  }
}

function trackingLocatorFor(tabId) {
  return trackingLocator(paneLocatorFor(tabId));
}

function trackingLocator(loc) {
  return loc && (loc.lastIndexOf('/messages/', 0) === 0 ? 'chat' : loc === '/messages' ? 'messages:page' : loc.substr(1));
}

function trackingCategory(cat) {
  return cat === 'global' ? 'announcement' : cat;
}

function updateTabsWithKeptState() {
  var args = Array.prototype.slice.call(arguments);
  var howKept = args.pop();
  args.push(function (tab) {
    setIcon(howKept, tab);
    api.tabs.emit(tab, 'kept', {kept: howKept});
  });
  forEachTabAt.apply(null, args);
}

function setIcon(howKept, tab) {
  log('[setIcon] tab:', tab.id, 'how:', howKept);
  var icons = {
    'private': 'icons/url_red',
    'public': 'icons/url_green',
    'organization': 'icons/url_blue'
  }
  api.icon.set(tab, (icons[howKept] || 'icons/url_dark') + (silence ? '_II' : '') + '.png');
}

function updateIconSilence(tab) {
  log('[updateIconSilence] tab:', tab.id, 'silent:', !!silence);
  if (tab.icon && tab.icon.indexOf('_II') < 0 !== !silence) {
    api.icon.set(tab, tab.icon.replace(/(?:_II)?\.png/, (silence ? '_II' : '') + '.png'));
  }
}

function postBookmarks(supplyBookmarks, bookmarkSource, libraryId) {
  return;
  log('[postBookmarks]', libraryId);
  supplyBookmarks(function (bookmarks) {
    ajax('POST', '/ext/libraries/' + libraryId + '/bookmarks', {bookmarks: bookmarks, source: bookmarkSource}, function (o) {
      log('[postBookmarks] resp:', o);
    });
  });
}

function whenTabFocused(tab, key, callback) {
  if (api.tabs.isFocused(tab)) {
    callback(tab);
  } else {
    (tab.focusCallbacks = tab.focusCallbacks || {})[key] = callback;
  }
}
// ===== Browser event listeners

// kifi icon in location bar
api.icon.on.click.add(function (tab) {
  if (silence) {
    unsilence(tab);
  } else {
    api.tabs.emit(tab, 'button_click', getDefaultPaneLocator(), {queue: 1});
  }
});

api.tabs.on.focus.add(function(tab) {
  log('#b8a', '[tabs.on.focus] %i %o', tab.id, tab);
  for (var key in tab.focusCallbacks) {
    tab.focusCallbacks[key](tab);
  }
  delete tab.focusCallbacks;
  kifify(tab);
  if (prefs && prefs.hideSocialTooltip) {
    scheduleAutoEngage(tab, 'keepers');
  }
});

api.tabs.on.blur.add(function(tab) {
  log('#b8a', '[tabs.on.blur] %i %o', tab.id, tab);
  clearAutoEngageTimer(tab, 'keepers');
});

api.tabs.on.loading.add(function(tab) {
  log('#b8a', '[tabs.on.loading] %i %o', tab.id, tab);
  kifify(tab);
});

api.tabs.on.unload.add(function(tab, historyApi) {
  log('#b8a', '[tabs.on.unload] %i %o', tab.id, tab);
  var tabs = tabsByUrl[tab.nUri];
  for (var i = tabs && tabs.length; i--;) {
    if (tabs[i] === tab) {
      tabs.splice(i, 1);
    }
  }
  if (!tabs || !tabs.length) {
    delete tabsByUrl[tab.nUri];
    delete pageData[tab.nUri];
    delete notificationLists[tab.nUri];
  }
  for (var loc in tabsByLocator) {
    var tabs = tabsByLocator[loc];
    if (tabs) {
      for (var i = tabs.length; i--;) {
        if (tabs[i] === tab) {
          tabs.splice(i, 1);
        }
      }
      if (!tabs.length) {
        delete tabsByLocator[loc];
      }
    } else {
      api.errors.push({error: Error('tabsByLocator array undefined'), params: {loc: loc, type: typeof tabs, in: loc in tabsByLocator}});
    }
  }
  clearAutoEngageTimer(tab, 'keepers');
  delete tab.nUri;
  delete tab.count;
  delete tab.engaged;
  delete tab.focusCallbacks;
  if (historyApi) {
    api.tabs.emit(tab, 'reset');
  }
});

api.on.beforeSearch.add(throttle(primeSearch, 50000));
function primeSearch(whence) {
  return;
  if (me && enabled('search')) {
    ajax('search', 'POST', '/ext/search/prime' + ('agos'.indexOf(whence) >= 0 ? '?w=' + whence : ''));
  }
}

var searchPrefetchCache = {};  // for searching before the results page is ready
api.on.search.add(function prefetchResults(query, whence) {
  if (!me || !enabled('search')) return;
  log('[prefetchResults] prefetching for query:', query);
  var entry = searchPrefetchCache[query];
  if (!entry) {
    entry = searchPrefetchCache[query] = {callbacks: [], response: null};
    searchOnServer({query: query, whence: whence}, gotPrefetchedResults.bind(null, query, entry));
  } else {
    api.timers.clearTimeout(entry.expireTimeout);
  }
  entry.expireTimeout = api.timers.setTimeout(cullPrefetchedResults.bind(null, query, entry), 10000);
});

function gotPrefetchedResults(query, entry, response) {
  api.timers.clearTimeout(entry.expireTimeout);
  if (entry.callbacks.length) {
    while (entry.callbacks.length) {
      entry.callbacks.shift()(response);
    }
    cullPrefetchedResults(query, entry);
  } else {
    entry.response = response;
    entry.expireTimeout = api.timers.setTimeout(cullPrefetchedResults.bind(null, query, entry), 10000);
  }
}

function cullPrefetchedResults(key, val) {
  if (searchPrefetchCache[key] === val) {
    delete searchPrefetchCache[key];
  }
}

function getPrefetchedResults(query, cb) {
  var entry = searchPrefetchCache[query];
  if (entry) {
    var consume = function (r) {
      log('[getPrefetchedResults]', query, r);
      cb(r);
    };
    if (entry.response) {
      consume(entry.response);
      delete searchPrefetchCache[query];
    } else {
      entry.callbacks.push(consume);
    }
  }
  return !!entry;
}

function removeFromThread(keepId) {
  removeNotification(keepId);
  forEachTabAtLocator('/messages/' + keepId, function (tab) {
    api.tabs.emit(tab, 'show_pane', {
      trigger: 'evictedAutoNavigate',
      locator: getDefaultPaneLocator(),
      redirected: false
    }, {queue: 1});
  });
}

// ===== Local storage

function stored(key) {
  return api.storage[qualify(key)];
}

function store(key, value) {
  var qKey = qualify(key), prev = api.storage[qKey];
  if (value != null && prev !== String(value)) {
    log('[store] %s = %s (was %s)', key, value, prev);
    api.storage[qKey] = value;
  }
}

function unstore(key) {
  delete api.storage[qualify(key)];
}

function qualify(key) {
  return api.mode.isDev() ? key + '@dev' : key;
}

// ===== Helper functions

function enabled(setting) {
  return stored('_' + setting) !== 'n';
}

function loadDrafts() {
  if (me) {
    var drafts = stored('drafts_' + me.id);
    if (drafts) {
      return JSON.parse(LZ.decompress(drafts));
    }
  }
  return {};
}

function storeDrafts(drafts) {
  if (me) {
    var key = 'drafts_' + me.id;
    drafts = JSON.stringify(drafts);
    if (drafts === '{}') {
      unstore(key);
    } else {
      api.storage[qualify(key)] = LZ.compress(drafts);
    }
  }
}

function saveDraft(key, draft) {
  log('[saveDraft]', key);
  if (!key) return;
  var now = draft.saved = Date.now();
  var drafts = loadDrafts();
  for (var k in drafts) {
    var d = drafts[k];
    if (now - d.saved > 259.2e6) { // 3 days
      log('[saveDraft] culling', k, d, Date(d.saved));
      delete drafts[k];
    }
  }
  drafts[key] = draft;
  storeDrafts(drafts);
}

function discardDraft(keys) {
  var drafts = loadDrafts(), draft;
  for (var i = 0; i < keys.length; i++) {
    var key = keys[i];
    if (key in drafts) {
      draft = drafts[key];
      log('[discardDraft]', key, draft);
      delete drafts[key];
    }
  }
  if (draft) {
    storeDrafts(drafts);
  }
  return draft;
}

function loadRecentLibs() {
  if (stored('user_id') === me.id) {
    try {
      return JSON.parse(stored('recent_libraries'));
    } catch (e) {
    }
  }
  return [];
}

function storeRecentLib(id) {
  var ids = loadRecentLibs();
  for (var i; (i = ids.indexOf(id)) >= 0;) {
    ids.splice(i, 1);
  }
  ids.unshift(id);
  if (ids.length > 3) {
    ids.length = 3;
  }
  store('recent_libraries', JSON.stringify(ids));
}

function scheduleAutoEngage(tab, type) {
  // Note: Caller should verify that tab.url is not kept and that the tab is still at tab.url.
  var secName = type + 'Sec', timerName = type + 'Timer';
  if (tab[secName] == null || tab[timerName]) return;
  log('[scheduleAutoEngage]', tab.id, type);
  tab[timerName] = api.timers.setTimeout(function autoEngage() {
    delete tab[secName];
    delete tab[timerName];
    log('[autoEngage]', tab.id, type);
    api.tabs.emit(tab, 'auto_engage', type, {queue: 1});
  }, tab[secName] * 1000);
}

function clearAutoEngageTimer(tab, type) {
  var secName = type + 'Sec', timerName = type + 'Timer';
  if (tab[timerName]) {
    api.timers.clearTimeout(tab[timerName]);
    delete tab[timerName];
  }
}

function compilePatterns(arr) {
  for (var i = 0; i < arr.length; i++) {
    arr[i] = new RegExp(arr[i], '');
  }
  return arr;
}

function dontShowFtueAgain(type) {
  return;
  var prefName = {
    e: 'showExtMsgIntro',
    m: 'showExtMoveIntro',
    q: 'quoteAnywhereFtue'
  }[type];
  if (!prefName) {
    return;
  }
  (prefs || {})[prefName] = false;
  ajax('POST', '/ext/pref/' + prefName + '?show=false');
}

function toResults(contacts, q, me, n, exclude, includeSelf) {
  exclude = (exclude || []).filter(Boolean);

  var sf = global.scoreFilter || require('./scorefilter').scoreFilter;
  if (!includeSelf) {
    contacts = contacts.filter(idIsNot(me.id));
  } else if (!contacts.some(idIs(me.id)) && (q ? sf.filter(q, [me], getName).length : contacts.length < n)) {
    appendUserResult(contacts, n, me);
  }
  if (!contacts.some(idIs(SUPPORT.id)) && (q ? sf.filter(q, [SUPPORT], getName).length : contacts.length < n)) {
    appendUserResult(contacts, n, SUPPORT);
  }
  var results = contacts
    .filter(function (elem) { return exclude.indexOf(elem.id || elem.email) === -1; })
    .slice(0, n)
    .map(toContactResult, {sf: sf, q: q});
  if (results.length < n && q && !exclude.some(idIs(q)) && !results.some(emailIs(q))) {
    results.push({id: 'q', q: q, isValidEmail: emailRe.test(q)});
  }
  return results;
}

function toContactResult(o) {
  if (o.name) {
    o.nameParts = this.sf.splitOnMatches(this.q, o.name);
  }
  if (o.email) {
    var i = o.email.indexOf('@');
    o.emailParts = this.sf.splitOnMatches(this.q, o.email.substr(0, i));
    var n = o.emailParts.length;
    if (n % 2) {
      o.emailParts[n - 1] += o.email.substr(i);
    } else {
      o.emailParts.push(o.email.substr(i));
    }
    if (!o.id) {
      o.id = o.email;
    }
  }
  return o;
}

function appendUserResult(contacts, n, user) {
  if (contacts.length >= n) {
    contacts.length = n - 1;
  }
  var i = contacts.filter(idIsNot(undefined)).length;
  contacts.splice(i, 0, clone(user));
}

function unique(arr, by) {
  var o = arr.reduce(function (o, d) {
    return o[by(d)] = d, o;
  }, {});
  return Object.keys(o).map(function (k) { return o[k]; });
}
function reTest(s) {
  return function (re) {return re.test(s)};
}
function idIs(id) {
  return function (o) {return o.id === id};
}
function idIsNot(id) {
  return function (o) {return o.id !== id};
}
function idIsIn(ids) {
  return function (o) {return ids.indexOf(o.id) >= 0; };
}
function getId(o) {
  return o.id;
}
function getName(o) {
  return o.name;
}
function emailIs(email) {
  return function (o) {return o.email === email};
}
function isMine(o) {
  return o.mine;
}
function isNotMine(o) {
  return !o.mine;
}
function isSecret(o) {
  return o.secret;
}
function libraryIdIs(id) {
  return function (o) {return o.libraryId === id};
}
function libraryIdIsNot(id) {
  return function (o) {return o.libraryId !== id};
}
function getTag(o) {
  return o.tag;
}
function tagNotIn(tags) {
  return function (o) {return tags.indexOf(o.tag) < 0};
}
function notIn(arr) {
  return function (x) {return arr.indexOf(x) < 0};
}
function getThreadId(n) {
  return n.thread;
}
function idToThread(id) {
  return notificationsById[id];
}
function endsWith(ch) {
  return function (s) { return s.slice(-1) === ch; };
}
function setProp(name, val) {
  return function (o) { o[name] = val; };
}
function filterDuplicates(accessor) {
  var map = {};
  return function (o) {
    var v = accessor(o);
    if (v in map) {
      return false;
    } else {
      map[v] = true;
      return true;
    }
  }
}
function extend(o, o1) {
  for (var k in o1) {
    o[k] = o1[k];
  }
  return o;
}
function clone(o) {
  return extend({}, o);
}
function makeTagObj(tag) {
  return this ? {tag: tag, parts: this.sf.splitOnMatches(this.q, tag)} : {tag: tag};
}
function makeObjectsForEmailAddresses(id) {
  return id.indexOf('@') < 0 ? id : {kind: 'email', email: id};
}

function devUriOr(uri) {
  return api.mode.isDev() ? 'http://dev.ezkeep.com:9000' : uri;
}
function apiUri(service) {
  return 'https://' + (service === '' ? 'api' : service) + '.kifi.com';
}
function serviceNameToUri(service) {
  switch (service) {
    case 'eliza':
      return elizaBaseUri();
    case 'search':
      return searchBaseUri();
    default:
      return apiBaseUri();
  }
}

var apiBaseUri = devUriOr.bind(null, apiUri(''));
var searchBaseUri = devUriOr.bind(null, apiUri('search'));
var elizaBaseUri = devUriOr.bind(null, apiUri('eliza'));

var webBaseUri = devUriOr.bind(null, 'https://www.kifi.com');
var admBaseUri = devUriOr.bind(null, 'https://admin.kifi.com');

function getPrefs(next) {
  return;
  ajax('GET', '/ext/prefs?version=2', function gotPrefs(o) {
    log('[gotPrefs]', o);
    if (me) {
      me = standardizeUser(o.user, organizations);
      prefs = o.prefs;
      eip = o.eip;
      //socket.send(['eip', eip]);
    }
    if (next) next();
  });
}

function searchRecipients(query, limit, offset, searchFor) {
  limit = limit || 10;
  offset = offset || 0;
  searchFor = searchFor || { user: true, email: true, library: false };
  var requested = Object.keys(searchFor).filter(function (k) { return searchFor[k]; }).join(',');

  return new Promise(function (resolve, reject) {
    ajax('GET', '/api/1/keeps/suggestRecipients', {query: query, limit: limit, offset, requested: requested}, resolve, reject);
  });
}

var defaultLibraries;
var defaultLibrariesTime;
var EXPIRE_DEFAULT_LIBRARIES = 60000 * 2;
function getDefaultLibraries() {
  if (!defaultLibraries || +new Date() - defaultLibrariesTime > EXPIRE_DEFAULT_LIBRARIES) {
    defaultLibraries = searchRecipients('', 20, null, {library: true});
    defaultLibrariesTime = +new Date();
  }
  return defaultLibraries;
}

function getKeepsByPage(pageUri) {
  return new Promise(function (resolve, reject) {
    ajax('POST', '/api/1/pages/query', {url: pageUri}, resolve, reject);
  });
}

function getKeep(keepId) {
  return new Promise(function (resolve, reject) {
    ajax('GET', '/api/1/keeps/' + keepId, resolve, reject);
  });
}

function getKeepActivity(keepId, limit, fromTime) {
  return new Promise(function (resolve, reject) {
    ajax('GET', '/api/1/keeps/' + keepId + '/activity', {limit: limit, fromTime: fromTime}, resolve, reject);
  });
}

function getKeepAndActivity(keepId) {
  return Promise.all([
    getKeep(keepId),
    getKeepActivity(keepId)
  ])
  .then(function (vals) {
    var keepResponseData = vals[0];
    var activityResponseData = vals[1];
    return {
      keep: keepResponseData.keep,
      page: keepResponseData.page,
      activity: activityResponseData
    }
  });
}

function setKeepTitle(keepId, newTitle, source) {
  return new Promise(function (resolve, reject) {
    ajax('POST', '/api/1/keeps/' + keepId + '/title', { newTitle: newTitle, source: source }, resolve, reject);
  });
}

function sendKeepReply(keepId, data) {
  return new Promise(function (resolve, reject) {
    ajax('POST', '/api/1/keeps/' + keepId + '/messages', data, resolve, reject);
  });
}

function sendKeep(data) {
  return new Promise(function (resolve, reject) {
    ajax('POST', '/api/1/keeps', data, resolve, reject)
  });
}

function updateKeepReciepients(keepId, data) {
  return new Promise(function (resolve, reject) {
    ajax('POST', '/api/1/keeps/' + keepId + '/recipients', data, resolve, reject);
  });
}

function getUrlPatterns(next) {
  return;
  ajax('GET', '/ext/pref/rules', function gotUrlPatterns(o) {
    log('[gotUrlPatterns]', o);
    if (o && o.url_patterns) {
      urlPatterns = compilePatterns(o.url_patterns);
    }
    if (next) next();
  });
}

function throttle(func, wait, opts) {  // underscore.js
  var context, args, result;
  var timeout = null;
  var previous = 0;
  opts || (opts = {});
  var later = function() {
    previous = opts.leading === false ? 0 : Date.now();
    timeout = null;
    result = func.apply(context, args);
    context = args = null;
  };
  return function() {
    var now = Date.now();
    if (!previous && opts.leading === false) previous = now;
    var remaining = wait - (now - previous);
    context = this;
    args = arguments;
    if (remaining <= 0) {
      api.timers.clearTimeout(timeout);
      timeout = null;
      previous = now;
      result = func.apply(context, args);
      context = args = null;
    } else if (!timeout && opts.trailing !== false) {
      timeout = api.timers.setTimeout(later, remaining);
    }
    return result;
  };
}

function arrayBufferToBase64(buffer) {
  var binary = '';
  var bytes = new Uint8Array(buffer);
  var len = bytes.byteLength;
  for (var i = 0; i < len; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return api.util.btoa(binary);
}

//                           |---- IP v4 address ---||- subs -||-- core --|  |----------- suffix -----------| |- name --|    |-- port? --|
var domainRe = /^https?:\/\/(\d{1,3}(?:\.\d{1,3}){3}|[^:\/?#]*?([^.:\/?#]+)\.(?:[^.:\/?#]{2,}|com?\.[a-z]{2})|[^.:\/?#]+)\.?(?::\d{2,5})?(?:$|\/|\?|#)/;
// Very loose matching. Allows for equality between completely different pages on the same domain.
function sameOrSimilarBaseDomain(url1, url2) {
  if (url1 === url2) {
    return true;
  }
  var m1 = url1.match(domainRe);
  var m2 = url2.match(domainRe);
  // hostnames match exactly or core domain without subdomains and TLDs match (e.g. "google" in docs.google.fr and www.google.co.uk)
  return m1[1] === m2[1] || m1[2] === (m2[2] || 0);
}

var protoRe = /^(https?:|)\/\//;
function verySimilarUrls(url1, url2) {
  if (url1 && url2) {
    var noHash1 = url1.replace(protoRe, '').split('#')[0];
    var noHash2 = url2.replace(protoRe, '').split('#')[0];
    return noHash1 === noHash2;
  }
  return false;
}

// ===== Session management

var me, mySysLibIds, organizations, prefs, experiments, eip, socket, silence, onLoadingTemp;

function authenticate(callback, retryMs) {
  return;
  var origInstId = stored('installation_id');
  if (!origInstId) {
    store('prompt_to_import_bookmarks', true);
  }
  ajax('POST', '/ext/start', {
    installation: origInstId,
    version: api.version
  },
  function done(data) {
    log('[authenticate:done] reason: %s session: %o', api.loadReason, data);
    unstore('logout');
    getDefaultLibraries(); // prime the cache

    api.toggleLogging(data.experiments.indexOf('extension_logging') >= 0);
    me = standardizeUser(data.user, data.orgs);
    mySysLibIds = data.libraryIds;
    organizations = data.orgs;
    experiments = data.experiments;
    eip = data.eip;
    socket = null;
    logEvent.catchUp();
    tracker.catchUp();

    urlPatterns = compilePatterns(data.patterns);
    store('installation_id', data.installationId);

    api.tabs.on.loading.remove(onLoadingTemp), onLoadingTemp = null;
    emitAllTabs('me_change', me);
    callback();
  },
  function fail(xhr) {
    log('[authenticate:fail] xhr.status:', xhr.status);
    if (!xhr.status || xhr.status >= 500) {  // server down or no network connection, so consider retrying
      if (retryMs) {
        api.timers.setTimeout(authenticate.bind(null, callback, Math.min(60000, retryMs * 1.5)), retryMs);
      }
    } else if (!origInstId) {
      api.tabs.selectOrOpen(webBaseUri() + '/');
      api.tabs.on.loading.add(onLoadingTemp = function(tab) {
        // if kifi.com home page, retry first authentication
        if (tab.url.replace(/\/(?:#.*)?$/, '') === webBaseUri()) {
          api.tabs.on.loading.remove(onLoadingTemp), onLoadingTemp = null;
          authenticate(callback, retryMs);
        }
      });
    }
  });
}

function lightFlush() {
  // Friendly cache dump
  unstore('recent_libraries');

  pageData = {};
  notificationLists = {};
  notificationsById = {};
  messageData = {};
  keepData = {};
  contactSearchCache = null;
  urlPatterns = null;
  guideData = null;

  authenticate(function () {
    getLatestThreads();
    getUrlPatterns(getPrefs.bind(null, api.noop));
    log('[lightFlush] flush successful');
  });

}

function clearSession() {
  if (me) {
    storeDrafts({});
    unstore('recent_libraries');
  }
  clearDataCache();

  if (me) {
    unstore('user_id');
    api.tabs.each(function (tab) {
      api.icon.set(tab, 'icons/url_gray.png');
      api.tabs.emit(tab, 'me_change', null);
      delete tab.nUri;
      delete tab.count;
      delete tab.engaged;
      delete tab.focusCallbacks;
    });
  }
  me = mySysLibIds = prefs = experiments = eip = null;

  if (silence) {
    api.timers.clearTimeout(silence.timeout);
    silence = null;
  }
  if (socket) {
    // socket.close();
    socket = null;
  }
}

function deauthenticate() {
  log('[deauthenticate]');
  tracker.track('user_clicked_pane', {type: 'settings', action: 'loggedOut'});
  tracker.catchUp();
  tracker.sendBatch();
  clearSession();
  store('logout', Date.now());
  ajax('DELETE', '/ext/auth');
}

// ===== Main, executed upon install (or reinstall), update, re-enable, and browser start

api.errors.wrap(authenticate.bind(null, function() {
  // if (api.loadReason === 'install') {
  //   log('[main] fresh install');
  //   var baseUri = webBaseUri();
  //   var focused = api.tabs.getFocused();

  //   var currentKifiTab;
  //   if (focused && focused.url && focused.url.indexOf('https://www.kifi.com') === 0) {
  //     currentKifiTab = focused;
  //   }

  //   var primaryKifiTab = api.tabs.anyAt(baseUri + '/install') || api.tabs.anyAt(baseUri + '/');

  //   var otherKifiSiteTab;
  //   api.tabs.each(function (tab) {
  //     if (tab.url.indexOf('https://www.kifi.com') === 0) { // Order doesn't matter at this point, just grab one.
  //       otherKifiSiteTab = tab;
  //     }
  //   });

  //   var tab = currentKifiTab || primaryKifiTab || otherKifiSiteTab;
  //   if (tab) {
  //     api.tabs.select(tab.id);
  //   } else {
  //     api.tabs.open(baseUri + '/signup');
  //   }
  // }
}, 3000))();
