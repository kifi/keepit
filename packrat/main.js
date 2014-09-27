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

var libraries;
var pageData = {}; // normUrl => PageData
var threadLists = {}; // normUrl => ThreadList (special keys: 'all', 'sent', 'unread')
var threadsById = {}; // threadId => thread (notification JSON)
var messageData = {}; // threadId => [message, ...]; TODO: evict old threads from memory
var contactSearchCache;
var urlPatterns;
var guidePages;

function clearDataCache() {
  log('[clearDataCache]');
  tabsByUrl = {};
  tabsByLocator = {};
  threadReadAt = {};
  for (var tabId in timeouts) {
    api.timers.clearTimeout(timeouts[tabId]);
    delete timeouts[tabId];
  }

  libraries = null;
  pageData = {};
  threadLists = {};
  threadsById = {};
  messageData = {};
  contactSearchCache = null;
  urlPatterns = null;
  guidePages = null;
}

// ===== Error reporting

(function (ab) {
  ab.setProject('95815', '603568fe4a88c488b6e2d47edca59fc1');
  ab.addReporter(function airbrake(notice, opts) {
    log.apply(null, ['#c00', '[airbrake]'].concat(notice.errors));
    notice.params = breakLoops(notice.params);
    notice.context.environment = api.isPackaged() && !api.mode.isDev() ? 'production' : 'development';
    notice.context.version = api.version;
    notice.context.userAgent = api.browser.userAgent;
    notice.context.userId = me && me.id;
    api.request('POST', 'https://api.airbrake.io/api/v3/projects/' + opts.projectId + '/notices?key=' + opts.projectKey, notice, function (o) {
      log('[airbrake]', o.url);
    });
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
        if (o.hasOwnProperty(k)) {
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
    this.sensitive = o.sensitive;
    this.shown = o.shown;
    this.keepers = o.keepers || [];
  },
  howKept: function () {
    var keeps = this.keeps;
    if (keeps.length) {
      var mine = keeps.filter(isMine);
      if (mine.length) {
        return mine.every(isSecret) ? 'private' : 'public';
      }
      return 'other';
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
function ajax(service, method, uri, data, done, fail) {  // method and uri are required
  if (httpMethodRe.test(service)) { // shift args if service is missing
    fail = done, done = data, data = uri, uri = method, method = service, service = 'api';
  }
  if (typeof data === 'function') {  // shift args if data is missing and done is present
    fail = done, done = data, data = null;
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
  api.request(
    method, uri, data, done,
    fail || (method === 'GET' ? onGetFail.bind(null, uri, done, 1) : null));
}

function onGetFail(uri, done, failures, req) {
  if ([403,404].indexOf(req.status) < 0) {
    if (failures < 10) {
      var ms = failures * 2000;
      log('[onGetFail]', req.status, uri, failures, 'failure(s), will retry in', ms, 'ms');
      api.timers.setTimeout(
        api.request.bind(api, 'GET', uri, null, done, onGetFail.bind(null, uri, done, failures + 1)),
        ms);
    } else {
      log('[onGetFail]', req.status, uri, failures, 'failures, giving up');
    }
  }
}

// ===== Event logging

var tracker = {
  enabled: true,
  queue: [],
  batch: [],
  sendBatch: function () {
    if (this.batch.length > 0) {
      ajax('POST', '/ext/events', this.batch);
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
    if (this.enabled) {
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
    }
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
    socket.send(["log_event", ev]);
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
    socket.send(["log_event", ev]);
  }
}

// ===== WebSocket

function onSocketConnect() {
  getLatestThreads();

  // http data refresh
  getUrlPatterns(getPrefs);
}

function onSocketDisconnect(why, sec) {
  log('[onSocketDisconnect]', why, sec || '');
}

function getLatestThreads() {
  socket.send(['get_latest_threads', THREAD_BATCH_SIZE], gotLatestThreads);
}

function gotLatestThreads(arr, numUnreadUnmuted, numUnread, serverTime) {
  log('[gotLatestThreads]', arr, numUnreadUnmuted, numUnread, serverTime);

  var serverTimeDate = new Date(serverTime);
  var staleMessageIds = (threadLists.all || {ids: []}).ids.reduce(function (o, threadId) {
    o[threadsById[threadId].id] = true;  // message ID, not thread ID
    return o;
  }, {});

  threadsById = {};
  threadReadAt = {};
  arr.forEach(function (n) {
    standardizeNotification(n);
    threadsById[n.thread] = n;
    var ageMs = serverTimeDate - new Date(n.time);
    if (ageMs >= 0 && ageMs < 60000 && !staleMessageIds[n.id]) {
      handleRealTimeNotification(n);
    }
  });

  threadLists = {};
  threadLists.all = new ThreadList(threadsById, arr.map(getThreadId), null, numUnreadUnmuted);
  threadLists.sent = new ThreadList(threadsById, arr.filter(isSent).map(getThreadId));
  threadLists.unread = new ThreadList(threadsById, arr.filter(isUnread).map(getThreadId), numUnread);
  threadLists.all.includesOldest = arr.length < THREAD_BATCH_SIZE;
  threadLists.sent.includesOldest = arr.length < THREAD_BATCH_SIZE;
  threadLists.unread.includesOldest = threadLists.unread.ids.length >= numUnread;

  emitThreadsToTabsViewing('all', threadLists.all);
  forEachTabAtThreadList(sendUnreadThreadCount);

  ['sent', 'unread'].forEach(function (kind) {
    var tl = threadLists[kind];
    if (tl.includesOldest || tl.ids.length) {
      emitThreadsToTabsViewing(kind, tl);
    }
    if (!tl.includesOldest && tl.ids.length <= (kind === 'unread' ? 1 : 0)) {  // precaution to avoid ever having 0 of N unread threads loaded
      socket.send(['get_' + kind + '_threads', THREAD_BATCH_SIZE], gotFilteredThreads.bind(null, kind, tl));
    }
  });

  tellVisibleTabsNoticeCountIfChanged();

  messageData = {};
  forEachThreadOpenInPane(function (threadId) {
    socket.send(['get_thread', threadId]);
  });

  api.tabs.eachSelected(kifify);
}

function gotFilteredThreads(kind, tl, arr, numTotal) {
  log('[gotFilteredThreads]', kind, arr, numTotal || '');
  arr.forEach(function (n) {
    standardizeNotification(n);
    updateIfJustRead(n);
    threadsById[n.thread] = n;
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
  experiments: function (exp) {
    log('[socket:experiments]', exp);
    experiments = exp;
    api.toggleLogging(exp.indexOf('extension_logging') >= 0);
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
      for (var thId in threadsById) {
        var th = threadsById[thId];
        if (th.category === 'message') {
          updatePic(th.author);
          th.participants.forEach(updatePic);
        }
      }
    }
    function updatePic(u) {
      if (u.id === me.id) {
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
  thread_participants: function(threadId, participants) {
    log('[socket:thread_participants]', threadId, participants);
    var thread = threadsById[threadId];
    if (thread) {
      thread.participants = participants;
    }
    forEachTabAtLocator('/messages/' + threadId, function (tab) {
      api.tabs.emit(tab, 'participants', participants);  // TODO: send threadId too
    });
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
  all_notifications_visited: function(id, time) {
    log('[socket:all_notifications_visited]', id, time);
    markAllThreadsRead(id, time);
  },
  thread: function(o) {
    log('[socket:thread]', o);
    messageData[o.id] = o.messages;
    // Do we need to update muted state and possibly participants too? or will it come in thread_info?
    forEachTabAtLocator('/messages/' + o.id, emitThreadToTab.bind(null, o.id, o.messages));
  },
  message: function(threadId, message) {
    log('[socket:message]', threadId, message, message.nUrl);
    forEachTabAtLocator('/messages/' + threadId, function (tab) {
      api.tabs.emit(tab, 'message', {threadId: threadId, message: message, userId: me.id}, {queue: true});
    });
    var messages = messageData[threadId];
    if (messages) {
      insertUpdateChronologically(messages, message, 'createdAt');
    }
  },
  message_read: function(nUri, threadId, time, messageId) {
    log("[socket:message_read]", nUri, threadId, time);
    removeNotificationPopups(threadId);
    markRead(threadId, messageId, time);
  },
  message_unread: function(nUri, threadId, time, messageId) {
    log("[socket:message_unread]", nUri, threadId, time);
    markUnread(threadId, messageId);
  }
};

function emitAllTabs(name, data, options) {
  return api.tabs.each(function(tab) {
    api.tabs.emit(tab, name, data, options);
  });
}

function emitThreadInfoToTab(th, tab) {
  api.tabs.emit(tab, 'thread_info', th, {queue: 1});
}

function emitThreadToTab(id, messages, tab) {
  api.tabs.emit(tab, 'thread', {id: id, messages: messages}, {queue: 1});
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
    keeper: enabled('keeper'),
    sensitive: enabled('sensitive'),
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

var SUPPORT = {id: 'aa345838-70fe-45f2-914c-f27c865bdb91', firstName: 'Tamila, Kifi Help', lastName: '', name: 'Tamila, Kifi Help', pictureName: 'tmilz.jpg'};

api.port.on({
  deauthenticate: deauthenticate,
  prime_search: primeSearch,
  get_keeps: searchOnServer,
  get_keepers: function(_, respond, tab) {
    log('[get_keepers]', tab.id);
    var d = pageData[tab.nUri];
    respond(d ? {kept: d.kept, keepers: d.keepers, otherKeeps: 0} : {keepers: []});
  },
  keep: function (data, respond, tab) {
    log('[keep]', data);
    var d = pageData[tab.nUri];
    if (!d) {
      api.tabs.emit(tab, 'kept', {fail: true});
    } else if (!d.state) {
      d.state = 'keeping';
      var libraryId = data.libraryId || libraryIds[data.secret ? 1 : 0];
      ajax('POST', '/ext/libraries/' + libraryId + '/keeps', {
        url: data.url,
        title: data.title,
        image: data.image,
        canonical: data.canonical,
        og: data.og,
        guided: data.guided
      }, function done(keep) {
        log('[keep:done]', keep);
        delete d.state;
        // main and secret are mutually exclusive
        // var i = libraryIds.indexOf(libraryId);
        // if (i >= 0) {
        //   d.keeps = d.keeps.filter(libraryIdIsNot(libraryIds[1 - i]));
        // }
        // TODO: replace line below with code above once server supports keeping into multiple libraries
        d.keeps = d.keeps.filter(isNotMine);
        var j = d.keeps.findIndex(libraryIdIs(libraryId));
        if (j >= 0) {
          d.keeps[j] = keep;
        } else {
          d.keeps.push(keep);
        }
        var how = d.howKept();
        respond(keep);
        delete keep.imageStatusPath;
        forEachTabAt(tab.url, tab.nUri, function (tab) {
          setIcon(!!how, tab);
          api.tabs.emit(tab, 'kept', {kept: how});
        });
        notifyKifiAppTabs({type: 'keep', libraryId: libraryId, keepId: keep.id});
      }, function fail(o) {
        log('[keep:fail]', data.url, o);
        delete d.state;
        respond();
        forEachTabAt(tab.url, tab.nUri, function (tab) {
          api.tabs.emit(tab, 'kept', {kept: d.howKept(), fail: true});
        });
      });
      var i = libraryIds.indexOf(libraryId);
      if (i >= 0 && !d.keeps.length) {
        api.tabs.emit(tab, 'kept', {kept: i === 0 ? 'public' : 'private'});
      }
    }
  },
  unkeep: function (libraryId, respond, tab) {
    var d = pageData[tab.nUri];
    if (!d) {
      log('[unkeep] fail', libraryId || '');
      api.tabs.emit(tab, 'kept', {fail: true});
    } else if (d.state) {
      log('[unkeep] ignoring', libraryId || '', d.state);
    } else {
      var keep = d.findKeep(libraryId || libraryIds[0]) || d.findKeep(libraryIds[1]);
      if (!keep) {
        log('[unkeep] fail', libraryId || '');
        api.tabs.emit(tab, 'kept', {fail: true});
      } else {
        log('[unkeep] ', libraryId || '', keep);
        d.state = 'unkeeping';
        ajax('DELETE', '/ext/libraries/' + keep.libraryId + '/keeps/' + keep.id, function done() {
          log('[unkeep:done]');
          delete d.state;
          d.keeps = d.keeps.filter(idIsNot(keep.id));
          respond(true);
          var how = d.howKept();
          forEachTabAt(tab.url, tab.nUri, function (tab) {
            setIcon(!!how, tab);
            api.tabs.emit(tab, 'kept', {kept: how});
          });
          notifyKifiAppTabs({type: 'unkeep', libraryId: keep.libraryId, keepId: keep.id});
        }, function fail() {
          log('[unkeep:fail]', d.keepId);
          delete d.state;
          respond();
          api.tabs.emit(tab, 'kept', {kept: d.howKept() || null, fail: true});
        });
        if (d.keeps.length === 1) {
          api.tabs.emit(tab, 'kept', {kept: null});
        }
      }
    }
  },
  keeps_and_libraries: function (_, respond, tab) {
    var d = pageData[tab.nUri];
    ajax('GET', '/ext/libraries', function (o) {
      libraries = o.libraries;
      var keeps = d ? d.keeps : [];
      respond({keeps: keeps, libraries: o.libraries});
      // preload keep details
      keeps.forEach(function (keep) {
        ajax('GET', '/ext/libraries/' + keep.libraryId + '/keeps/' + keep.id, function (details) {
          keep.details = details;
          // TODO: trigger get_keep response if any are waiting
        });
      });
    }, respond);
  },
  filter_libraries: function (q, respond) {
    var sf = global.scoreFilter || require('./scorefilter').scoreFilter;
    respond(sf.filter(q, libraries, getName).map(function (lib) {
      lib = clone(lib);
      lib.nameParts = sf.splitOnMatches(q, lib.name);
      return lib;
    }));
  },
  create_library: function (data, respond) {
    ajax('POST', '/ext/libraries', data, function (library) {
      if (libraries) {
        libraries.push(library);
      }
      respond(library);
      notifyKifiAppTabs({type: 'create_library', libraryId: library.id});
    }, respond.bind(null, null));
  },
  delete_library: function (libraryId, respond) {
    ajax('DELETE', '/ext/libraries/' + libraryId, function () {
      if (libraries) {
        libraries = libraries.filter(idIsNot(libraryId));
      }
      respond(true);
      notifyKifiAppTabs({type: 'delete_library', libraryId: libraryId});
    }, respond.bind(null, false));
  },
  get_keep: function (libraryId, respond, tab) {
    var d = pageData[tab.nUri];
    if (d) {
      var details = d.keeps.find(libraryIdIs(libraryId)).details;
      // TODO: wait if details are not yet loaded
      respond(details);
    }
  },
  save_keep: function (data, respond, tab) {
    var d = pageData[tab.nUri];
    if (d) {
      var keep = d.keeps.find(libraryIdIs(data.libraryId));
      ajax('POST', '/ext/libraries/' + keep.libraryId + '/keeps/' + keep.id, data.updates, function () {
        if (keep.details) {
          ['title'].forEach(function (prop) {
            if (prop in data.updates) {
              keep.details[prop] = data.updates[prop];
            }
          });
        }
        respond(true);
      }, respond.bind(null, false));
    }
  },
  save_keep_image: function (data, respond, tab) {
    var d = pageData[tab.nUri];
    if (d) {
      var keep = d.keeps.find(libraryIdIs(data.libraryId));
      ajax('POST', '/ext/libraries/' + keep.libraryId + '/keeps/' + keep.id + '/image', {image: data.image}, function (resp) {
        if (keep.details) {
          keep.details.image = resp.image;
        }
        respond(true);
      }, respond.bind(null, false));
    }
  },
  keeper_shown: function(data, _, tab) {
    (pageData[tab.nUri] || {}).shown = true;
    logEvent('slider', 'sliderShown', data);
  },
  suppress_on_site: function(data, _, tab) {
    ajax('POST', '/ext/pref/keeperHidden', {url: tab.url, suppress: data});
    pageData[tab.nUri].neverOnSite = !!data;
  },
  get_suppressed: function(_, respond, tab) {
    var d = pageData[tab.nUri];
    if (d) {
      respond(d.neverOnSite);
    }
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
    for (var nUri in pageData) {
      if (nUri.match(hostRe)[1] == o.host) {
        pageData[nUri].position = o.pos;
      }
    }
    ajax("POST", "/ext/pref/keeperPosition", {host: o.host, pos: o.pos});
  },
  set_look_here_mode: function (on) {
    ajax('POST', '/ext/pref/lookHereMode?on=' + on);
    if (prefs) prefs.lookHereMode = on;
  },
  set_enter_to_send: function(data) {
    ajax('POST', '/ext/pref/enterToSend?enterToSend=' + data);
    if (prefs) prefs.enterToSend = data;
  },
  set_max_results: function(n, respond) {
    ajax('POST', '/ext/pref/maxResults?n=' + n, respond);
    tracker.track('user_changed_setting', {category: 'search', type: 'maxResults', value: n});
    if (prefs) prefs.maxResults = n;
  },
  stop_showing_external_messaging_intro: function(action) {
    ajax('POST', '/ext/pref/showExtMsgIntro?show=false');
    api.tabs.each(function (tab) {
      api.tabs.emit(tab, 'hide_external_messaging_intro');
    });
    if (prefs) prefs.showExtMsgIntro = false;
    tracker.track('user_was_notified', {
      action: 'click',
      subaction: action,
      channel: 'kifi',
      subchannel: 'tooltip',
      category: 'extMsgFTUE'
    });
  },
  track_showing_external_messaging_intro: function() {
    tracker.track('user_was_notified', {
      action: 'open',
      channel: 'kifi',
      subchannel: 'tooltip',
      category: 'extMsgFTUE'
    });
  },
  log_search_event: function(data) {
    ajax('search', 'POST', '/search/events/' + data[0], data[1]);
  },
  import_contacts: function (source) {
    api.tabs.selectOrOpen(webBaseUri() + '/contacts/import');
    tracker.track('user_clicked_pane', {
      type: source,
      action: 'importGmail',
      subsource: 'composeTypeahead'
    });
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
      respond(canvas.toDataURL('image/png'));
    });
  },
  load_draft: function (data, respond, tab) {
    var drafts = loadDrafts();
    if (data.to) {
      respond(drafts[tab.nUri] || drafts[tab.url]);
    } else {
      respond(drafts[currentThreadId(tab)]);
    }
  },
  save_draft: function (data, _, tab) {
    var drafts = loadDrafts();
    if (data.html || data.to && data.to.length) {
      saveDraft(data.to ? tab.nUri || tab.url : currentThreadId(tab), data);
    } else {
      discardDraft(data.to ? [tab.nUri, tab.url] : [currentThreadId(tab)]);
    }
  },
  send_message: function(data, respond, tab) {
    discardDraft([tab.nUri, tab.url]);
    data.extVersion = api.version;
    data.source = api.browser.name;
    data.eip = eip;
    data.recipients = data.recipients.map(makeObjectsForEmailAddresses);
    ajax('eliza', 'POST', '/eliza/messages', data, function(o) {
      log('[send_message] resp:', o);
      // thread (notification) JSON comes via socket
      messageData[o.parentId] = o.messages;
      respond({threadId: o.parentId});
    });
  },
  send_reply: function(data, respond) {
    var threadId = data.threadId;
    delete data.threadId;
    discardDraft([threadId]);
    data.extVersion = api.version;
    data.source = api.browser.name;
    data.eip = eip;
    ajax('eliza', 'POST', '/eliza/messages/' + threadId, data, logAndRespond, logErrorAndRespond);
    function logAndRespond(o) {
      log('[send_reply] resp:', o);
      respond(o);
    }
    function logErrorAndRespond(req) {
      log('#c00', '[send_reply] resp:', req);
      respond({status: req.status});
    }
  },
  message_rendered: function(o, _, tab) {
    whenTabFocused(tab, o.threadId, function (tab) {
      markRead(o.threadId, o.messageId, o.time);
      socket.send(['set_message_read', o.messageId]);
    });
  },
  set_message_read: function (o) {
    markRead(o.threadId, o.messageId, o.time);
    socket.send(['set_message_read', o.messageId]);
  },
  set_message_unread: function (o) {
    markUnread(o.threadId, o.messageId);
    socket.send(['set_message_unread', o.messageId]);
  },
  get_page_thread_count: function(_, __, tab) {
    sendPageThreadCount(tab, null, true);
  },
  thread: function(id, _, tab) {
    var th = threadsById[id];
    if (th) {
      emitThreadInfoToTab(th, tab);
    } else {
      // TODO: remember that this tab needs this thread info until it gets it or its pane changes?
      socket.send(['get_one_thread', id], function (th) {
        standardizeNotification(th);
        updateIfJustRead(th);
        threadsById[th.thread] = th;
        emitThreadInfoToTab(th, tab);
      });
    }
    var msgs = messageData[id];
    if (msgs) {
      emitThreadToTab(id, msgs, tab);
    } else {
      // TODO: remember that this tab needs this thread until it gets it or its pane changes?
      socket.send(['get_thread', id]);
    }
  },
  thread_list: function(o, _, tab) {
    var uri = tab.nUri || tab.url;
    var tl = threadLists[o.kind === 'page' ? uri : o.kind];
    if (tl) {
      if (o.kind === 'unread') { // detect, report, recover from unread threadlist constistency issues
        if (tl.ids.map(idToThread).filter(isUnread).length < tl.ids.length) {
          getLatestThreads();
          api.errors.push({error: Error('Read threads found in threadLists.unread'), params: {
            threads: tl.ids.map(idToThread).map(function (th) {
              return {thread: th.thread, id: th.id, time: th.time, unread: th.unread, readAt: threadReadAt[th.thread]};
            })
          }});
          return;
        } else if (tl.ids.length === 0 && tl.numTotal > 0) {
          socket.send(['get_unread_threads', THREAD_BATCH_SIZE], gotFilteredThreads.bind(null, 'unread', tl));
          api.errors.push({error: Error('No unread threads available to show'), params: {threadList: tl}});
          return;
        }
      }
      emitThreadsToTab(o.kind, tl, tab);
      if (o.kind === 'page') {  // prefetch
        tl.ids.forEach(function (id) {
          if (!messageData[id]) {
            socket.send(['get_thread', id]);
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
    var list = threadLists[o.kind === 'page' ? tab.nUri : o.kind];
    var n = list ? list.ids.length : 0;
    for (var i = n - 1; i >= 0 && threadsById[list.ids[i]].time < o.time; i--);
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
      socket.send(socketMessage, function (arr) {
        arr.forEach(function (th) {
          standardizeNotification(th);
          updateIfJustRead(th);
          threadsById[th.thread] = th;
        });
        var includesOldest = arr.length < THREAD_BATCH_SIZE;
        var list = threadLists[o.kind === 'page' ? tab.nUri : o.kind];
        if (list && list.ids[list.ids.length - 1] === o.threadId) {
          list.insertOlder(arr.map(getThreadId));
          list.includesOldest = includesOldest;
        }
        // TODO: may also want to append/update sent & unread if this is the all kind
        respond({threads: arr, includesOldest: includesOldest});
      });
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
      tracker.track('user_viewed_pane', {type: loc.lastIndexOf('/messages/', 0) === 0 ? 'chat' : loc.substr(1)});
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
      var threadId = threadLists.all && threadLists.all.ids[0];
      msgId = threadId && threadsById[threadId].id;
    }
    if (msgId) {
      socket.send(['set_all_notifications_visited', msgId]);
    }
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
    if (o.name === 'emails') {
      ajax('POST', '/ext/pref/email/message/' + o.value, function () {
        if (prefs) {
          prefs.messagingEmails = o.value;
        }
        onSettingCommitted();
      });
    } else {
      store('_' + o.name, o.value ? 'y' : 'n');
      onSettingCommitted();
      if (o.name === 'keeper') {
        var sensitive = enabled('sensitive');
        api.tabs.each(function (tab) {
          var d = tab.nUri && pageData[tab.nUri];
          if (d && !d.neverOnSite && !(d.sensitive && sensitive)) {
            api.tabs.emit(tab, 'show_keeper', o.value);
          }
        });
      } else if (o.name === 'sensitive') {
        api.tabs.each(function (tab) {
          var d = tab.nUri && pageData[tab.nUri];
          if (d && !d.neverOnSite && d.sensitive) {
            api.tabs.emit(tab, 'show_keeper', !o.value);
          }
        });
      }
    }
    tracker.track('user_changed_setting', {
      category:
        ~['sounds','popups','emails'].indexOf(o.name) ? 'notification' :
        ~['keeper','sensitive'].indexOf(o.name) ? 'keeper' :
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
  search_contacts: function (data, respond, tab) {
    if (!contactSearchCache) {
      contactSearchCache = new (global.ContactSearchCache || require('./contact_search_cache').ContactSearchCache)(3600000);
    }
    var results = contactSearchCache.get(data);
    if (results) {
      respond(results);
    } else {
      ajax('GET', '/ext/contacts/search', {query: data.q, limit: data.n}, function (contacts) {
        var sf = global.scoreFilter || require('./scorefilter').scoreFilter;
        if (!data.includeSelf) {
          contacts = contacts.filter(idIsNot(me.id));
        } else if (!contacts.some(idIs(me.id)) && (data.q ? sf.filter(data.q, [me], getName).length : contacts.length < data.n)) {
          appendUserResult(contacts, data.n, me);
        }
        if (!contacts.some(idIs(SUPPORT.id)) && (data.q ? sf.filter(data.q, [SUPPORT], getName).length : contacts.length < data.n)) {
          appendUserResult(contacts, data.n, SUPPORT);
        }
        var results = contacts.map(toContactResult, {sf: sf, q: data.q});
        if (results.length < data.n && data.q && !data.participants.some(idIs(data.q)) && !results.some(emailIs(data.q))) {
          results.push({id: 'q', q: data.q, isValidEmail: emailRe.test(data.q)});
        }
        respond(results);
        contactSearchCache.put(data, results);
      }, function () {
        respond(null);
      });
    }
  },
  delete_contact: function (email, respond) {
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
    api.tabs.open(webBaseUri() + data.path);
    if (data.source === 'keeper') {
      tracker.track('user_clicked_pane', {type: 'keeper', action: 'visitKifiSite'});
    }
  },
  close_tab: function (_, __, tab) {
    api.tabs.close(tab.id);
  },
  open_deep_link: function(link, _, tab) {
    if (link.inThisTab || tab.nUri === link.nUri) {
      awaitDeepLink(link, tab.id);
    } else {
      var tabs = tabsByUrl[link.nUri];
      if ((tab = tabs ? tabs[0] : api.tabs.anyAt(link.nUri))) {  // page's normalized URI may have changed
        awaitDeepLink(link, tab.id);
        api.tabs.select(tab.id);
      } else {
        api.tabs.open(link.nUri, function (tabId) {
          awaitDeepLink(link, tabId);
        });
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
    if (guidePages && /^#guide\/\d\/\d/.test(link.locator)) {
      var step = +link.locator.substr(7, 1);
      switch (step) {
        case 1:
          pageData[link.url] = new PageData({shown: true});
          tabsByUrl[link.url] = tabsByUrl[link.url] || [];
          break;
        case 2:
          var page = guidePages[+link.locator.substr(9, 1)];
          var tagId = link.locator.substr(11);
          var query = page.query.replace(/\+/g, ' ');
          var entry = searchPrefetchCache[query] = {
            response: pimpSearchResponse({
              uuid: '00000000-0000-0000-0000-000000000000',
              query: query,
              hits: [{
                bookmark: {
                  title: page.title,
                  url: page.url,
                  tags: tagId ? [tagId] : [],
                  matches: page.matches
                },
                users: [],
                count: 1,
                score: 0,
                isMyBookmark: true,
                isPrivate: false
              }],
              myTotal: 1,
              friendsTotal: 0,
              othersTotal: 816,
              mayHaveMore: false,
              show: true,
              context: 'guide'
            })
          };
          entry.expireTimeout = api.timers.setTimeout(cullPrefetchedResults.bind(null, query, entry), 10000);
          break;
      }
    }
  },
  add_participants: function(data) {
    socket.send(['add_participants_to_thread', data.threadId, data.ids.map(makeObjectsForEmailAddresses)]);
  },
  is_muted: function(threadId, respond) {
    var th = threadsById[threadId];
    respond({
      success: Boolean(th),
      response: Boolean(th && th.muted)
    });
  },
  mute_thread: function(threadId) {
    socket.send(['mute_thread', threadId]);
    setMuted(threadId, true);
  },
  unmute_thread: function(threadId) {
    socket.send(['unmute_thread', threadId]);
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
  import_bookmarks: function() {
    unstore('prompt_to_import_bookmarks');
    postBookmarks(api.bookmarks.getAll, 'INIT_LOAD');
  },
  import_bookmarks_public: function () {
    unstore('prompt_to_import_bookmarks');
    postBookmarks(api.bookmarks.getAll, 'INIT_LOAD', true);
  },
  import_bookmarks_declined: function() {
    unstore('prompt_to_import_bookmarks')
  },
  toggle_mode: function () {
    if (!api.isPackaged()) {
      api.mode.toggle();
    }
  },
  start_guide: function (pages, _, tab) {
    guidePages = pages;
    api.tabs.emit(tab, 'guide', {step: 0, pages: guidePages, x: !experiments || experiments.indexOf('guide_forced') < 0});
    unsilence(false);
  },
  track_guide: function (stepParts) {
    tracker.track('user_viewed_pane', {type: 'guide' + stepParts.join('')});
  },
  track_guide_choice: function (pageIdx) {
    tracker.track('user_clicked_pane', {type: 'guide01', action: 'chooseExamplePage', subaction: guidePages[pageIdx].track});
  },
  resume_guide: function (step, _, tab) {
    if (guidePages) {
      api.tabs.emit(tab, 'guide', {
        step: step,
        pages: guidePages,
        page: 0 // TODO: guess based on tab.url
      });
    }
  },
  end_guide: function (stepParts) {
    tracker.track('user_clicked_pane', {type: 'guide' + stepParts.join(''), action: 'closeGuide'});
    if (api.isPackaged()) {
      guidePages = null;
    }
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

function standardizeUser(u) {
  u.name = (u.firstName + ' ' + u.lastName).trim();
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
  var n0 = threadsById[n.thread];
  // proceed only if we don't already have this notification or a newer one for the same thread
  if (!n0 || n0.id !== n.id && n0.time < n.time) {
    threadsById[n.thread] = n;
    updateIfJustRead(n);
    var o = {all: true, page: true, unread: n.unread, sent: isSent(n)};
    for (var kind in o) {
      if (o[kind]) {
        var tl = threadLists[kind === 'page' ? n.url : kind];
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

function updateIfJustRead(th) {
  if (th.unread && threadReadAt[th.thread] >= th.time) {
    th.unread = false;
    th.unreadAuthors = th.unreadMessages = 0;
  }
}

// messageId is of last read message
function markUnread(threadId, messageId) {
  delete threadReadAt[threadId];
  var th = threadsById[threadId];
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
    }(threadLists.unread));
    if (!th.muted) {
      var tlKeys = ['all', th.url];
      if (isSent(th)) {
        tlKeys.push('sent');
      }
      tlKeys.forEach(function (key) {
        var tl = threadLists[key];
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
  var th = threadsById[threadId];
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
          socket.send(['get_unread_threads', THREAD_BATCH_SIZE], gotFilteredThreads.bind(null, 'unread', tl));
        }
      }
    }(threadLists.unread));
    if (!th.muted) {
      var tlKeys = ['all', 'unread', th.url];
      if (isSent(th)) {
        tlKeys.push('sent');
      }
      tlKeys.forEach(function (key) {
        var tl = threadLists[key];
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
  for (var id in threadsById) {
    var th = threadsById[id];
    if (th.unread && (th.id === messageId || th.time <= time)) {
      th.unread = false;
      th.unreadAuthors = th.unreadMessages = 0;
      if (timeDate - new Date(th.time) < 180000) {
        removeNotificationPopups(id);
      }
    }
  }

  var tlUnread = threadLists.unread;
  if (tlUnread) {
    for (var i = tlUnread.ids.length; i--;) {
      var id = tlUnread.ids[i];
      if (!threadsById[id].unread) {
        tlUnread.ids.splice(i, 1);
      }
    }
    tlUnread.numTotal = tlUnread.ids.length;  // any not loaded are older and now marked read
  }
  var tlAll = threadLists.all;
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
  var thread = threadsById[threadId];
  if (thread && thread.muted !== muted) {
    thread.muted = muted;
    if (thread.unread) {
      var tlKeys = ['all', thread.url];
      if (isSent(thread)) {
        tlKeys.push('sent');
      }
      tlKeys.forEach(function (key) {
        var tl = threadLists[key];
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
  var tl = threadLists.unread;
  if (tl) {
    api.tabs.emit(tab, 'unread_thread_count', tl.numTotal, {queue: 1});
  } // else will be pushed to tab when known
}

function sendPageThreadCount(tab, tl, load) {
  var uri = tab.nUri || tab.url;
  tl = tl || threadLists[uri];
  if (tl) {
    api.tabs.emit(tab, 'page_thread_count', {count: tl.numTotal, id: tl.numTotal === 1 ? tl.ids[0] : undefined}, {queue: 1});
  } else if (load) {
    socket.send(['get_page_threads', tab.url, THREAD_BATCH_SIZE], gotPageThreads.bind(null, uri));
  } // will be pushed to tab when known
}

function awaitDeepLink(link, tabId, retrySec) {
  var loc = link.locator;
  if (loc) {
    api.timers.clearTimeout(timeouts[tabId]);
    delete timeouts[tabId];
    var tab = api.tabs.get(tabId);
    if (tab && sameOrLikelyRedirected(link.url || link.nUri, tab.nUri || tab.url)) {
      log('[awaitDeepLink]', tabId, link);
      if (loc.lastIndexOf('#guide/', 0) === 0) {
        api.tabs.emit(tab, 'guide', {
          step: +loc.substr(7, 1),
          pages: guidePages,
          page: +loc.substr(9, 1),
          x: !experiments || experiments.indexOf('guide_forced') < 0
        }, {queue: 1});
      } else if (loc.indexOf('#compose') >= 0) {
        api.tabs.emit(tab, 'compose', {trigger: 'deepLink'}, {queue: 1});
      } else {
        api.tabs.emit(tab, 'show_pane', {
          trigger: 'deepLink',
          locator: loc,
          redirected: (link.url || link.nUri) !== (tab.nUri || tab.url)
        }, {queue: 1});
      }
    } else if ((retrySec = retrySec || .5) < 5) {
      log('[awaitDeepLink]', tabId, 'retrying in', retrySec, 'sec');
      timeouts[tabId] = api.timers.setTimeout(awaitDeepLink.bind(null, link, tabId, retrySec + .5), retrySec * 1000);
    }
    if (loc.lastIndexOf('/messages/', 0) === 0) {
      var threadId = loc.substr(10);
      if (!messageData[threadId]) {
        socket.send(['get_thread', threadId]);  // a head start
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

var threadLocatorRe = /^\/messages\/[a-z0-9-]+$/;
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
  if (!threadLists.all) return;
  api.tabs.eachSelected(function (tab) {
    if (tab.count !== threadLists.all.numUnreadUnmuted) {
      tab.count = threadLists.all.numUnreadUnmuted;
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
    maxHits: 5,
    lastUUID: request.lastUUID,
    context: request.context,
    kifiVersion: api.version,
    w: request.whence};

  ajax('search', 'GET', '/search', params, function (resp) {
    log('[searchOnServer] %i hits', resp.hits.length);
    respond(pimpSearchResponse(resp, request.filter, resp.hits.length < params.maxHits && (params.context || params.f)));
  });
  return true;
}

function pimpSearchResponse(o, filter, noMore) {
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
  return o;
}

function kifify(tab) {
  log('[kifify]', tab.id, tab.url, tab.icon || '', tab.nUri || '', me ? '' : 'no session');
  if (!tab.icon) {
    api.icon.set(tab, 'icons/k_gray' + (silence ? '.paused' : '') + '.png');
  } else {
    updateIconSilence(tab);
  }

  if (!me) {
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

  if (threadLists.all && tab.count !== threadLists.all.numUnreadUnmuted) {
    tab.count = threadLists.all.numUnreadUnmuted;
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
    if (!tab.engaged) {
      kififyWithPageData(tab, d);
    }
  } else {
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
  setIcon(!!d.howKept(), tab);
  if (silence) return;

  var hide = d.neverOnSite || !enabled('keeper') || d.sensitive && enabled('sensitive');
  api.tabs.emit(tab, 'init', {  // harmless if sent to same page more than once
    kept: d.howKept(),
    position: d.position,
    hide: hide
  }, {queue: 1});

  // consider triggering automatic keeper behavior on page to engage user (only once)
  if (!tab.engaged) {
    tab.engaged = true;
    if (!d.kept && !hide) {
      if (urlPatterns && urlPatterns.some(reTest(tab.url))) {
        log('[initTab]', tab.id, 'restricted');
      } else if (d.shown) {
        log('[initTab]', tab.id, 'shown before');
      } else if (d.keepers.length) {
        tab.keepersSec = 20;
        if (api.tabs.isFocused(tab)) scheduleAutoEngage(tab, 'keepers');
      }
    }
  }
}

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
    var oldTh = threadsById[th.thread];
    if (!oldTh || oldTh.time <= th.time) {
      updateIfJustRead(th);
      if (oldTh && oldTh.unread && !th.unread) {
        markRead(th.thread, th.id, th.time);
      }
      threadsById[th.thread] = th;
      if (oldTh) {
        updatedThreadIds.push(th.thread);
      }
    }
  });

  // reusing (sharing) the page ThreadList of an earlier normalization of the URL if possible
  var pt = threadLists[nUri] || threadLists[threads.length ? threads[0].url : ''];
  if (pt) {
    pt.ids = threads.map(getThreadId);
    pt.numTotal = numTotal;
  } else {
    pt = new ThreadList(threadsById, threads.map(getThreadId), numTotal, null);
  }
  pt.includesOldest = threads.length < THREAD_BATCH_SIZE;
  threadLists[nUri] = pt;

  // sending new page threads and count to any tabs on this page with pane open to page threads
  forEachTabAtUriAndLocator(uri, nUri, '/messages', emitThreadsToTab.bind(null, 'page', pt));
  forEachTabAt(uri, nUri, function (tab) {
    sendPageThreadCount(tab, pt); // TODO: only if pane is open
  });

  // updating tabs currently displaying any updated threads
  var requested = {};
  updatedThreadIds.forEach(function (threadId) {
    socket.send(['get_thread', threadId]);
    requested[threadId] = true;
  });

  // prefetch any unread threads
  pt.forEachUnread(function (threadId) {
    if (!messageData[threadId] && !requested[threadId]) {
      socket.send(['get_thread', threadId]);
      requested[threadId] = true;
    }
  });
}

function isSent(th) {
  return th.firstAuthor != null && th.participants[th.firstAuthor].id === me.id;
}

function isUnread(th) {
  return th.unread;
}

function paneIsOpen(tabId) {
  var hasThisTabId = idIs(tabId);
  for (var loc in tabsByLocator) {
    if (tabsByLocator[loc].some(hasThisTabId)) {
      return true;
    }
  }
}

function setIcon(kept, tab) {
  log('[setIcon] tab:', tab.id, 'kept:', kept);
  api.icon.set(tab, (kept ? 'icons/k_blue' : 'icons/k_dark') + (silence ? '.paused' : '') + '.png');
}

function updateIconSilence(tab) {
  log('[updateIconSilence] tab:', tab.id, 'silent:', !!silence);
  if (tab.icon && tab.icon.indexOf('.paused') < 0 !== !silence) {
    api.icon.set(tab, tab.icon.substr(0, tab.icon.indexOf('.')) + (silence ? '.paused' : '') + '.png');
  }
}

function postBookmarks(supplyBookmarks, bookmarkSource, makePublic) {
  log('[postBookmarks]');
  supplyBookmarks(function(bookmarks) {
    if (makePublic) {
      bookmarks.forEach(function (bookmark) {
        bookmark.isPrivate = false;
      });
    }
    log('[postBookmarks] bookmarks:', bookmarks);
    ajax("POST", "/bookmarks/add", {
        bookmarks: bookmarks,
        source: bookmarkSource},
      function(o) {
        log('[postBookmarks] resp:', o);
      });
  });
}

function clone(o) {
  var c = {};
  for (var k in o) {
    c[k] = o[k];
  }
  return c;
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
  scheduleAutoEngage(tab, 'keepers');
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
    delete threadLists[tab.nUri];
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
  if (me && enabled('search')) {
    ajax('search', 'GET', '/search/warmUp', {w: whence});
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
  var drafts = loadDrafts(), found;
  for (var i = 0; i < keys.length; i++) {
    var key = keys[i];
    if (key in drafts) {
      log('[discardDraft]', key, drafts[key]);
      delete drafts[key];
      found = true;
    }
  }
  if (found) {
    storeDrafts(drafts);
  }
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

function reTest(s) {
  return function (re) {return re.test(s)};
}
function idIs(id) {
  return function (o) {return o.id === id};
}
function idIsNot(id) {
  return function (o) {return o.id !== id};
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
function getThreadId(n) {
  return n.thread;
}
function idToThread(id) {
  return threadsById[id];
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
  ajax('GET', '/ext/prefs?version=2', function gotPrefs(o) {
    log('[gotPrefs]', o);
    if (me) {
      me = standardizeUser(o.user);
      prefs = o.prefs;
      eip = o.eip;
      socket.send(['eip', eip]);
    }
    if (next) next();
  });
}

function getUrlPatterns(next) {
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

//                           |---- IP v4 address ---||- subs -||-- core --|  |----------- suffix -----------| |- name --|    |-- port? --|
var domainRe = /^https?:\/\/(\d{1,3}(?:\.\d{1,3}){3}|[^:\/?#]*?([^.:\/?#]+)\.(?:[^.:\/?#]{2,}|com?\.[a-z]{2})|[^.:\/?#]+)\.?(?::\d{2,5})?(?:$|\/|\?|#)/;
function sameOrLikelyRedirected(url1, url2) {
  if (url1 === url2) {
    return true;
  }
  var m1 = url1.match(domainRe);
  var m2 = url2.match(domainRe);
  // hostnames match exactly or core domain without subdomains and TLDs match (e.g. "google" in docs.google.fr and www.google.co.uk)
  return m1[1] === m2[1] || m1[2] === (m2[2] || 0);
}

// ===== Session management

var me, libraryIds, prefs, experiments, eip, socket, silence, onLoadingTemp;

function authenticate(callback, retryMs) {
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

    api.toggleLogging(data.experiments.indexOf('extension_logging') >= 0);
    me = standardizeUser(data.user);
    libraryIds = data.libraryIds;
    experiments = data.experiments;
    eip = data.eip;
    socket = socket || api.socket.open(
      elizaBaseUri().replace(/^http/, 'ws') + '/eliza/ext/ws?version=' + api.version + (eip ? '&eip=' + eip : ''),
      socketHandlers, onSocketConnect, onSocketDisconnect);
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

function clearSession() {
  if (me) {
    unstore('drafts-' + me.id);
    api.tabs.each(function (tab) {
      api.icon.set(tab, 'icons/k_gray.png');
      api.tabs.emit(tab, 'me_change', null);
      delete tab.nUri;
      delete tab.count;
      delete tab.engaged;
      delete tab.focusCallbacks;
    });
  }
  me = libraryIds = prefs = experiments = eip = null;
  if (socket) {
    socket.close();
    socket = null;
  }
  if (silence) {
    api.timers.clearTimeout(silence.timeout);
    silence = null;
  }
  clearDataCache();
}

function deauthenticate() {
  log('[deauthenticate]');
  clearSession();
  store('logout', Date.now());
  ajax('DELETE', '/ext/auth');
}

// ===== Main, executed upon install (or reinstall), update, re-enable, and browser start

api.timers.setTimeout(api.errors.wrap(function() {
  console.log(LZ.decompress('ਠऀ䚱䔾㩢䟭ሻᩣᑲƌѐӠ匰ༀዠ஠଱»䥠䢨䧤z⏅ö䈣ᨰຠㆠ答Ĕఠ䚜Ž䔧ऀ捭❰ݔ䠳ᩬ椹唴KὢᄂƼĩ䂠 '));
}));

api.errors.wrap(authenticate.bind(null, function() {
  if (api.loadReason === 'install') {
    log('[main] fresh install');
    var baseUri = webBaseUri();
    var tab = api.tabs.anyAt(baseUri + '/install') || api.tabs.anyAt(baseUri + '/');
    var await = awaitDeepLink.bind(null, {locator: '#guide/0', url: baseUri});
    if (tab) {
      api.tabs.select(tab.id);
      api.tabs.navigate(tab.id, baseUri);
      timeouts[tab.id] = api.timers.setTimeout(await.bind(null, tab.id), 900); // be sure we're off previous page
    } else {
      api.tabs.open(baseUri, await);
    }
  }
}, 3000))();
