/*jshint globalstrict:true */
'use strict';

var global = this;
var api = api || require('./api');
var log = log || api.log;

var THREAD_BATCH_SIZE = 8;

//                          | sub |    |------- country domain -------|-- generic domain --|           |-- port? --|
var domainRe = /^https?:\/\/[^\/]*?((?:[^.\/]+\.[^.\/]{2,3}\.[^.\/]{2}|[^.\/]+\.[^.\/]{2,6}|localhost))(?::\d{2,5})?(?:$|\/)/;
var hostRe = /^https?:\/\/([^\/]+)/;

var tabsByUrl = {}; // normUrl => [tab]
var tabsByLocator = {}; // locator => [tab]
var tabsTagging = []; // [tab]
var threadReadAt = {}; // threadID => time string (only if read recently in this browser)
var deepLinkTimers = {}; // tabId => timeout identifier

// ===== Cached data from server

var pageData = {}; // normUrl => PageData
var threadLists = {}; // normUrl => ThreadList (special keys: 'all', 'sent', 'unread')
var threadsById = {}; // threadId => thread (notification JSON)
var messageData = {}; // threadId => [message, ...]; TODO: evict old threads from memory
var friends;
var friendsById;
var friendSearchCache;
var ruleSet = {rules: {}};
var urlPatterns;
var tags;
var tagsById;

function clearDataCache() {
  log('[clearDataCache]')();
  tabsByUrl = {};
  tabsByLocator = {};
  tabsTagging = [];
  threadReadAt = {};
  for (var tabId in deepLinkTimers) {
    api.timers.clearTimeout(deepLinkTimers[tabId]);
  }
  deepLinkTimers = {};

  pageData = {};
  threadLists = {};
  threadsById = {};
  messageData = {};
  friends = null;
  friendsById = null;
  friendSearchCache = null;
  ruleSet = {rules: {}};
  urlPatterns = null;
  tags = null;
  tagsById = null;
}

// ===== Error reporting

(function (ab) {
  ab.setProject('95815', '603568fe4a88c488b6e2d47edca59fc1');
  ab.addReporter(function airbrake(notice, opts) {
    notice.params = breakLoops(notice.params);
    notice.context.environment = api.isPackaged() && !api.mode.isDev() ? 'production' : 'development';
    notice.context.version = api.version;
    notice.context.userAgent = api.browser.userAgent;
    notice.context.userId = me && me.id;
    api.request('POST', 'https://api.airbrake.io/api/v3/projects/' + opts.projectId + '/notices?key=' + opts.projectKey, notice, function (o) {
      log('#c00', '[airbrake] report', o.id, o.url)();
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
          if (++n > 1000) break;
          o2[k] = visit(o[k], d + 1);
        }
      }
      return o2;
    }
  }
}(this.Airbrake || require('./airbrake.min').Airbrake));

// ===== Types/Classes

var ThreadList = ThreadList || require('./threadlist').ThreadList;

function PageData() {
}

function indexOfTag(tags, tagId) {
  for (var i = 0, len = tags.length; i < len; i++) {
    if (tags[i].id === tagId) {
      return i;
    }
  }
  return -1;
}

function addTag(tags, tag) {
  var index = indexOfTag(tags, tag.id);
  if (index === -1) {
    return tags.push(tag);
  }

  if (tag.name) {
    tags[index].name = tag.name;
  }
  return 0;
}

function removeTag(tags, tagId) {
  var index = indexOfTag(tags, tagId);
  if (index === -1) {
    return null;
  }
  return tags.splice(index, 1)[0];
}

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

function ajax(service, method, uri, data, done, fail) {  // method and uri are required
  if (service === 'GET' || service === 'POST') { // shift args if service is missing
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
  if (failures < 10) {
    var ms = failures * 2000;
    log('[onGetFail]', req.status, uri, failures, 'failure(s), will retry in', ms, 'ms')();
    api.timers.setTimeout(
      api.request.bind(api, 'GET', uri, null, done, onGetFail.bind(null, uri, done, failures + 1)),
      ms);
  } else {
    log('[onGetFail]', req.status, uri, failures, 'failures, giving up')();
  }
}

// ===== Event logging

var mixpanel = {
  enabled: true,
  queue: [],
  batch: [],
  sendBatch: function () {
    if (this.batch.length > 0) {
      var json = JSON.stringify(this.batch);
      var dataString = "data=" + api.util.btoa(unescape(encodeURIComponent(json)));
      api.postRawAsForm("https://api.mixpanel.com/track/", dataString);
      this.batch.length = 0;
    }
  },
  augmentAndBatch: function (data) {
    data.properties.token = 'cff752ff16ee39eda30ae01bb6fa3bd6';
    data.properties.distinct_id = me.id;
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
      log("#aaa", "[mixpanel.track] %s %o", eventName, properties)();
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

function logEvent(eventFamily, eventName, metaData, prevEvents) {
  if (eventFamily !== 'slider') {
    log("#800", "[logEvent] invalid event family:", eventFamily)();
    return;
  }
  var ev = {
    installId: stored('installation_id'), // ExternalId[KifiInstallation]
    eventFamily: eventFamily, // Category (see eventFamilies)
    eventName: eventName}; // Any key for this event
  if (metaData) {
    ev.metaData = metaData; // Any js object that you would like to attach to this event. i.e., number of total results shown, which result was clicked, etc.
  }
  if (prevEvents && prevEvents.length) {
    ev.prevEvents = prevEvents; // a list of previous ExternalId[Event]s that are associated with this action. The frontend determines what is associated with what.
  }
  log("#aaa", "[logEvent] %s %o", ev.eventName, ev)();
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
  getRules(getPrefs.bind(null, getTags.bind(null, getFriends)));
}

function onSocketDisconnect(why, sec) {
  log('[onSocketDisconnect]', why, sec || '')();
}

function getLatestThreads() {
  socket.send(['get_latest_threads', THREAD_BATCH_SIZE], gotLatestThreads);
}

function gotLatestThreads(arr, numUnreadUnmuted, numUnread, serverTime) {
  log('[gotLatestThreads]', arr, numUnreadUnmuted, numUnread, serverTime)();

  var serverTimeDate = new Date(serverTime);
  var staleMessageIds = (threadLists.all || {ids: []}).ids.reduce(function (o, threadId) {
    o[threadsById[threadId].id] = true;  // message ID, not thread ID
    return o;
  }, {});

  threadsById = {};
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
  log('[gotFilteredThreads]', kind, arr, numTotal || '')();
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
    log('[socket:denied]')();
    clearSession();
  },
  version: function (v) {
    log('[socket:version]', v)();
    if (api.version !== v) {
      api.requestUpdateCheck();
    }
  },
  experiments: function (exp) {
    log('[socket:experiments]', exp)();
    experiments = exp;
    api.toggleLogging(exp.indexOf('extension_logging') >= 0);
  },
  new_friends: function (fr) {
    log('[socket:new_friends]', fr)();
    if (friends) {
      for (var i = 0; i < fr.length; i++) {
        var f = standardizeUser(fr[i]);
        if (f.id in friendsById) {
          friends = friends.filter(idIsNot(f.id))
        }
        friends.push(f);
        friendsById[f.id] = f;
        friendSearchCache = null;
      }
    }
  },
  lost_friends: function (fr) {
    log('[socket:lost_friends]', fr)();
    if (friends) {
      for (var i = 0; i < fr.length; i++) {
        var f = fr[i];
        if (f.id in friendsById) {
          friends = friends.filter(idIsNot(f.id));
          delete friendsById[f.id];
          friendSearchCache = null;
        }
      }
    }
  },
  create_tag: onTagChangeFromServer.bind(null, 'create'),
  rename_tag: onTagChangeFromServer.bind(null, 'rename'),
  remove_tag: onTagChangeFromServer.bind(null, 'remove'),
  thread_participants: function(threadId, participants) {
    log('[socket:thread_participants]', threadId, participants)();
    var thread = threadsById[threadId];
    if (thread) {
      thread.participants = participants;
    }
    forEachTabAtLocator('/messages/' + threadId, function (tab) {
      api.tabs.emit(tab, 'participants', participants);  // TODO: send threadId too
    });
  },
  thread_muted: function(threadId, muted) {
    log("[socket:thread_muted]", threadId, muted)();
    setMuted(threadId, muted);
  },
  url_patterns: function(patterns) {
    log("[socket:url_patterns]", patterns)();
    urlPatterns = compilePatterns(patterns);
  },
  notification: function(n, th) {  // a new notification (real-time)
    log('[socket:notification]', n, th || '')();
    standardizeNotification(n);
    if (insertNewNotification(th ? standardizeNotification(th) : n)) {
      handleRealTimeNotification(n);
      tellVisibleTabsNoticeCountIfChanged();
    }
  },
  all_notifications_visited: function(id, time) {
    log('[socket:all_notifications_visited]', id, time)();
    markAllThreadsRead(id, time);
  },
  thread: function(o) {
    log('[socket:thread]', o)();
    messageData[o.id] = o.messages;
    // Do we need to update muted state and possibly participants too? or will it come in thread_info?
    forEachTabAtLocator('/messages/' + o.id, emitThreadToTab.bind(null, o.id, o.messages));
  },
  message: function(threadId, message) {
    log('[socket:message]', threadId, message, message.nUrl)();
    forEachTabAtLocator('/messages/' + threadId, function (tab) {
      api.tabs.emit(tab, 'message', {threadId: threadId, message: message, userId: me.id}, {queue: true});
    });
    var messages = messageData[threadId];
    if (messages) {
      insertUpdateChronologically(messages, message, 'createdAt');
    }
  },
  message_read: function(nUri, threadId, time, messageId) {
    log("[socket:message_read]", nUri, threadId, time)();
    removeNotificationPopups(threadId);
    markRead(threadId, messageId, time);
  },
  message_unread: function(nUri, threadId, time, messageId) {
    log("[socket:message_unread]", nUri, threadId, time)();
    markUnread(threadId, messageId);
  }
};

function emitAllTabs(name, data, options) {
  return api.tabs.each(function(tab) {
    api.tabs.emit(tab, name, data, options);
  });
}

function emitTabsByUrl(url, name, data, options) {
  return tabsByUrl[url].forEach(function(tab) {
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

function onAddTagResponse(result) {
  if (result.success) {
    var nUri = this.nUri,
      d = pageData[nUri],
      tag = result.response;
    if (addTag(tags, tag)) {
      tagsById[tag.id] = tag;
    }
    addTag(d.tags, tag);
    log('onAddTagResponse', tag, d.tags)();
    emitTabsByUrl(nUri, 'add_tag', tag);
    emitTabsByUrl(nUri, 'tagged', {
      tagged: true
    });
  }
}

function onRemoveTagResponse(tagId, result) {
  if (result.success) {
    var nUri = this.nUri,
      d = pageData[nUri];
    removeTag(d.tags, tagId);
    log('onRemoveTagResponse', tagId, d.tags)();
    emitTabsByUrl(nUri, 'remove_tag', {
      id: tagId
    });
    emitTabsByUrl(nUri, 'tagged', {
      tagged: d.tags.length
    });
  }
}

function onClearTagsResponse(result) {
  if (result.success) {
    var nUri = this.nUri;
    pageData[nUri].tags.length = 0;
    log('onClearTagsResponse', pageData[nUri].tags)();
    emitTabsByUrl(nUri, 'clear_tags');
    emitTabsByUrl(nUri, 'tagged', {
      tagged: false
    });
  }
}

function makeRequest(name, method, url, data, callbacks) {
  log("[" + name + "]", data)();
  ajax(method, url, data, function(response) {
    log("[" + name + "] response:", response)();
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
    log("[" + name + "] error:", response)();
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
  get_keeps: searchOnServer,
  get_keepers: function(_, respond, tab) {
    log("[get_keepers]", tab.id)();
    var d = pageData[tab.nUri] || {};
    respond({kept: d.kept, keepers: d.keepers || [], otherKeeps: d.otherKeeps || 0});
  },
  keep: function(data, _, tab) {
    log("[keep]", data)();
    (pageData[tab.nUri] || {}).kept = data.how;
    var bm = {
      title: data.title,
      url: data.url,
      canonical: data.canonical,
      og: data.og,
      isPrivate: data.how == "private"};
    postBookmarks(function(f) {f([bm])}, "HOVER_KEEP");
    forEachTabAt(tab.url, tab.nUri, function(tab) {
      setIcon(tab, data.how);
      api.tabs.emit(tab, "kept", {kept: data.how});
    });
    reloadKifiAppTabs();
  },
  unkeep: function(data, _, tab) {
    log("[unkeep]", data)();
    delete (pageData[tab.nUri] || {}).kept;
    ajax("POST", "/bookmarks/remove", data, function(o) {
      log("[unkeep] response:", o)();
    });
    forEachTabAt(tab.url, tab.nUri, function(tab) {
      setIcon(tab, false);
      api.tabs.emit(tab, "kept", {kept: null});
    });
  },
  set_private: function(data, _, tab) {
    log("[setPrivate]", data)();
    ajax('POST', '/bookmarks/update', data, function (o) {
      log("[setPrivate] response:", o)();
    });
    forEachTabAt(tab.url, tab.nUri, function(tab) {
      api.tabs.emit(tab, "kept", {kept: data.private ? "private" : "public"});
    });
  },
  set_title: function(data, respond) {
    ajax('POST', '/bookmarks/update', data, respond.bind(null, true), respond.bind(null, false));
  },
  keeper_shown: function(_, __, tab) {
    (pageData[tab.nUri] || {}).shown = true;  // server already notified via event log
  },
  suppress_on_site: function(data, _, tab) {
    ajax("POST", "/users/slider/suppress", {url: tab.url, suppress: data});
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
  set_enter_to_send: function(data) {
    ajax('POST', '/ext/pref/enterToSend?enterToSend=' + data);
    if (prefs) prefs.enterToSend = data;
  },
  set_max_results: function(n, respond) {
    ajax('POST', '/ext/pref/maxResults?n=' + n, respond);
    mixpanel.track('user_changed_setting', {category: 'search', type: 'maxResults', value: n});
    if (prefs) prefs.maxResults = n;
  },
  set_show_find_friends: function(show) {
    ajax('POST', '/ext/pref/showFindFriends?show=' + show);
    if (prefs) prefs.showFindFriends = show;
  },
  stop_showing_keeper_intro: function() {
    ajax('POST', '/ext/pref/showKeeperIntro?show=false');
    api.tabs.each(function (tab) {
      api.tabs.emit(tab, 'hide_keeper_intro');
    });
    if (prefs) prefs.showKeeperIntro = false;
  },
  set_show_search_intro: function(show) {
    ajax('POST', '/ext/pref/showSearchIntro?show=' + show);
    if (prefs) prefs.showSearchIntro = show;
  },
  useful_page: function(o, _, tab) {
    ajax('search', 'POST', '/search/events/browsed', [tab.url]);
  },
  log_event: function(data) {
    logEvent.apply(null, data);
  },
  log_search_event: function(data) {
    ajax('search', 'POST', '/search/events/' + data[0], data[1]);
  },
  invite_friends: function (where) {
    api.tabs.selectOrOpen(webBaseUri() + '/friends/invite');
    mixpanel.track('user_clicked_pane', {type: where, action: 'clickInviteFriends'});
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
    ajax('eliza', 'POST', '/eliza/messages', data, function(o) {
      log('[send_message] resp:', o)();
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
    ajax('eliza', 'POST', '/eliza/messages/' + threadId, data, logAndRespond, logErrorAndRespond);
    function logAndRespond(o) {
      log('[send_reply] resp:', o)();
      respond(o);
    }
    function logErrorAndRespond(req) {
      log('#c00', '[send_reply] resp:', req)();
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
      socket.send(['get_thread_info', id], function (th) {
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
      mixpanel.track('user_viewed_pane', {type: loc.lastIndexOf('/messages/', 0) === 0 ? 'chat' : loc.substr(1)});
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
  save_setting: function(o, respond, tab) {
    if (o.name === 'emails') {
      ajax('POST', '/ext/pref/email/message/' + !!o.value, onSettingCommitted);
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
    mixpanel.track('user_changed_setting', {
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
  web_base_uri: function(_, respond) {
    respond(webBaseUri());
  },
  search_friends: function(data, respond) {
    var sf = global.scoreFilter || require('./scorefilter').scoreFilter;
    var results;
    if (friendSearchCache) {
      results = friendSearchCache.get(data);
    } else {
      friendSearchCache = new (global.FriendSearchCache || require('./friend_search_cache').FriendSearchCache)(3600000);
    }
    if (!results) {
      var candidates = friendSearchCache.get({includeSelf: data.includeSelf, q: data.q.substr(0, data.q.length - 1)}) ||
        (data.includeSelf ?
           friends ? [me].concat(friends) : [me, SUPPORT] :
           friends || [SUPPORT]);
      results = sf.filter(data.q, candidates, getName);
      friendSearchCache.put(data, results);
    }
    respond((results.length > 4 ? results.slice(0, 4) : results).map(toFriendSearchResult, {sf: sf, q: data.q}));
  },
  open_deep_link: function(link, _, tab) {
    if (link.inThisTab || tab.nUri === link.nUri) {
      awaitDeepLink(link, tab.id);
    } else {
      var tabs = tabsByUrl[link.nUri];
      if ((tab = tabs ? tabs[0] : api.tabs.anyAt(link.nUri))) {  // page’s normalized URI may have changed
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
    api.tabs.emit(tab, 'open_to', {
      trigger: 'deepLink',
      locator: '/messages',
      composeTo: friendsById && friendsById[SUPPORT.id] || SUPPORT
    }, {queue: 1});
  },
  open_login_popup: function(o) {
    var baseUri = webBaseUri();
    api.popup.open({
      name: o.id || "kifi-popup",
      url: o.url,
      width: 1020,
      height: 530}, {
      navigate: function(url) {
        var popup = this;
        if (url == baseUri + "/#_=_" || url == baseUri + "/") {
          ajax("GET", "/ext/authed", function (loggedIn) {
            if (loggedIn !== false) {
              startSession(function() {
                log("[open_login_popup] closing popup")();
                popup.close();
              });
            }
          });
        }
      }
    });
  },
  logged_in: startSession.bind(null, api.noop),
  remove_notification: function (threadId) {
    removeNotificationPopups(threadId);
  },
  await_deep_link: function(link, _, tab) {
    awaitDeepLink(link, tab.id);
  },
  get_tags: function(_, respond, tab) {
    var d = pageData[tab.nUri],
      success = d ? true : false,
      response = null;

    if (success) {
      response = {all: tags, page: d.tags};

      if (tabsTagging.length) {
        tabsTagging = tabsTagging.filter(idIsNot(tab.id));
      }
      tabsTagging.push(tab);
    }

    respond({
      success: success,
      response: response
    });
  },
  create_and_add_tag: function(name, respond, tab) {
    makeRequest('create_and_add_tag', 'POST', '/tags/add', {
      name: name,
      url: tab.url
    }, [onAddTagResponse.bind(tab), respond]);
  },
  add_tag: function(tagId, respond, tab) {
    makeRequest('add_tag', 'POST', '/tags/' + tagId + '/addToKeep', {
      url: tab.url
    }, [onAddTagResponse.bind(tab), respond]);
  },
  remove_tag: function(tagId, respond, tab) {
    makeRequest('remove_tag', 'POST', '/tags/' + tagId + '/removeFromKeep', {
      url: tab.url
    }, [onRemoveTagResponse.bind(tab, tagId), respond]);
  },
  clear_tags: function(tagId, respond, tab) {
    makeRequest('clear_tags', 'POST', '/tags/clear', {
      url: tab.url
    }, [onClearTagsResponse.bind(tab), respond]);
  },
  add_participants: function(data) {
    socket.send(['add_participants_to_thread', data.threadId, data.userIds]);
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
  get_bookmark_count_if_should_import: function(_, respond) {
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
  import_bookmarks_declined: function() {
    unstore('prompt_to_import_bookmarks')
  },
  toggle_mode: function () {
    if (!api.isPackaged()) {
      api.mode.toggle();
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

function onTagChangeFromServer(op, tag) {
  if (!tags || !tagsById) return;
  var tagId = tag.id;
  switch (op) {
    case 'create':
    case 'rename':
      if (tagId in tagsById) {
        tagsById[tagId].name = tag.name;
      } else {
        tags.push(tag);
        tagsById[tagId] = tag;
      }
      break;
    case 'remove':
      tags = tags.filter(idIsNot(tagId));
      delete tagsById[tagId];
  }
  tabsTagging.forEach(function (tab) {
    api.tabs.emit(tab, 'tag_change', this);
  }, {op: op, tag: tag});
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
      th && th.time > time ? 'newer: ' + th.time : '')();
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
    api.timers.clearTimeout(deepLinkTimers[tabId]);
    delete deepLinkTimers[tabId];
    var tab = api.tabs.get(tabId);
    if (tab && (link.url || link.nUri).match(domainRe)[1] == (tab.nUri || tab.url).match(domainRe)[1]) {
      log('[awaitDeepLink]', tabId, link)();
      api.tabs.emit(tab, 'open_to', {
        trigger: 'deepLink',
        locator: loc,
        redirected: (link.url || link.nUri) !== (tab.nUri || tab.url)
      }, {queue: 1});
    } else if ((retrySec = retrySec || .5) < 5) {
      log('[awaitDeepLink]', tabId, 'retrying in', retrySec, 'sec')();
      deepLinkTimers[tabId] = api.timers.setTimeout(awaitDeepLink.bind(null, link, tabId, retrySec + .5), retrySec * 1000);
    }
    if (loc.lastIndexOf('/messages/', 0) === 0) {
      var threadId = loc.substr(10);
      if (!messageData[threadId]) {
        socket.send(['get_thread', threadId]);  // a head start
      }
    }
  } else {
    log('[awaitDeepLink] no locator', tabId, link)();
  }
}

var appRe = /^https?:\/\/(?:www\.)?kifi\.com(?:\/(?:|blog|profile|find|tag\/[a-z0-9-]+|friends(?:\/\w+)?))?(?:[?#].*)?$/;
function reloadKifiAppTabs() {
  for (var url in tabsByUrl) {
    if (appRe.test(url)) {
      var tabs = tabsByUrl[url];
      if (tabs) {
        tabs.forEach(reload);
      }
    }
  }
  function reload(tab) {
    api.tabs.reload(tab.id);
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
  if (request.first && getPrefetched(request, respond)) return;

  if (!me || !enabled('search')) {
    log('[searchOnServer] noop, me:', me)();
    respond({});
    return;
  }

  if (request.filter) {
    searchFilterCache[request.query] = {filter: request.filter, time: Date.now()};  // TODO: purge cache
  } else {
    delete searchFilterCache[request.query];
  }

  var maxHits = 5;
  var params = {
    q: request.query,
    f: request.filter && (request.filter.who !== 'a' ? request.filter.who : null), // f=a disables tail cutting
    maxHits: maxHits,
    lastUUID: request.lastUUID,
    context: request.context,
    kifiVersion: api.version};

  var respHandler = function(resp) {
    log('[searchOnServer] response:', resp)();
    resp.filter = request.filter;
    resp.me = me;
    resp.prefs = prefs || {maxResults: 1};
    resp.experiments = experiments;
    resp.admBaseUri = admBaseUri();
    resp.myTotal = resp.myTotal || 0;
    resp.friendsTotal = resp.friendsTotal || 0;
    resp.hits.forEach(processSearchHit);
    if (resp.hits.length < maxHits && (params.context || params.f)) {
      resp.mayHaveMore = false;
    }
    respond(resp);
  };

  ajax("search", "GET", "/search", params, respHandler);
  return true;
}

function processSearchHit(hit) {
  var tags = hit.bookmark && hit.bookmark.tags;
  if (tags && tags.length) {
    var tagNames = hit.bookmark.tagNames = [];
    for (var i = 0; i < tags.length; i++) {
      var tag = tagsById && tagsById[tags[i]];
      if (tag) {
        tagNames.push(tag.name);
      }
    }
  }
}

function kifify(tab) {
  log("[kifify]", tab.id, tab.url, tab.icon || '', tab.nUri || '', me ? '' : 'no session')();
  if (!tab.icon) {
    api.icon.set(tab, 'icons/k_gray' + (silence ? '.paused' : '') + '.png');
  } else {
    updateIconSilence(tab);
  }

  if (!me) {
    if (!stored('logout') || tab.url.indexOf(webBaseUri()) === 0) {
      ajax("GET", "/ext/authed", function(loggedIn) {
        if (loggedIn !== false) {
          startSession(function() {
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
    ajax('POST', '/ext/pageDetails', {url: url}, gotPageDetailsFor.bind(null, url, tab), function fail(xhr) {
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
  log('[stashTabByNormUri]', tab.id)();
}

function kififyWithPageData(tab, d) {
  log('[kififyWithPageData]', tab.id, tab.engaged ? 'already engaged' : '')();
  setIcon(tab, d.kept);
  if (silence) return;

  var hide = d.neverOnSite || !enabled('keeper') || d.sensitive && enabled('sensitive');
  api.tabs.emit(tab, 'init', {  // harmless if sent to same page more than once
    kept: d.kept,
    position: d.position,
    hide: hide,
    tags: d.tags,
    showKeeperIntro: prefs && prefs.showKeeperIntro
  }, {queue: 1});

  // consider triggering automatic keeper behavior on page to engage user (only once)
  if (!tab.engaged) {
    tab.engaged = true;
    if (!d.kept && !hide) {
      if (ruleSet.rules.url && urlPatterns.some(reTest(tab.url))) {
        log('[initTab]', tab.id, 'restricted')();
      } else if (ruleSet.rules.shown && d.shown) {
        log('[initTab]', tab.id, 'shown before')();
      } else {
        var focused = api.tabs.isFocused(tab);
        if (ruleSet.rules.scroll) {
          api.tabs.emit(tab, 'scroll_rule', ruleSet.rules.scroll, {queue: 1});
        }
        if ((ruleSet.rules.focus || [])[0] != null) {
          tab.buttonSec = ruleSet.rules.focus[0];
          if (focused) scheduleAutoEngage(tab, 'button');
        }
        if (d.keepers.length) {
          tab.keepersSec = 20;
          if (focused) scheduleAutoEngage(tab, 'keepers');
        }
      }
    }
  }
}

function gotPageDetailsFor(url, tab, resp) {
  var tabIsOld = api.tabs.get(tab.id) !== tab || url.split('#', 1)[0] !== tab.url.split('#', 1)[0];

  log('[gotPageDetailsFor]', tab.id, tabIsOld ? 'OLD' : '', url, resp)();

  var nUri = resp.normalized;
  var d = pageData[nUri] || new PageData;

  d.kept = resp.kept;
  d.tags = resp.tags || [];
  d.position = resp.position;
  d.neverOnSite = resp.neverOnSite;
  d.sensitive = resp.sensitive;
  d.shown = resp.shown;
  d.keepers = resp.keepers || [];
  d.keeps = resp.keeps || 0;
  d.otherKeeps = d.keeps - d.keepers.length - (d.kept === 'public');

  if (!tabIsOld) {
    pageData[nUri] = d;
    stashTabByNormUri(tab, nUri);
    kififyWithPageData(tab, d);
  }
}

function gotPageThreads(uri, nUri, threads, numTotal) {
  log('[gotPageThreads]', threads.length, 'of', numTotal, uri, nUri !== uri ? nUri : '')();

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
  var hasThisTabId = hasId(tabId);
  for (var loc in tabsByLocator) {
    if (tabsByLocator[loc].some(hasThisTabId)) {
      return true;
    }
  }
}

function setIcon(tab, kept) {
  log('[setIcon] tab:', tab.id, 'kept:', kept)();
  api.icon.set(tab, (kept ? 'icons/k_blue' : 'icons/k_dark') + (silence ? '.paused' : '') + '.png');
}

function updateIconSilence(tab) {
  log('[updateIconSilence] tab:', tab.id, 'silent:', !!silence)();
  if (tab.icon && tab.icon.indexOf('.paused') < 0 !== !silence) {
    api.icon.set(tab, tab.icon.substr(0, tab.icon.indexOf('.')) + (silence ? '.paused' : '') + '.png');
  }
}

function postBookmarks(supplyBookmarks, bookmarkSource) {
  log("[postBookmarks]")();
  supplyBookmarks(function(bookmarks) {
    log("[postBookmarks] bookmarks:", bookmarks)();
    ajax("POST", "/bookmarks/add", {
        bookmarks: bookmarks,
        source: bookmarkSource},
      function(o) {
        log("[postBookmarks] resp:", o)();
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
    api.tabs.emit(tab, 'button_click', null, {queue: 1});
  }
});

api.tabs.on.focus.add(function(tab) {
  log("#b8a", "[tabs.on.focus] %i %o", tab.id, tab)();
  for (var key in tab.focusCallbacks) {
    tab.focusCallbacks[key](tab);
  }
  delete tab.focusCallbacks;
  kifify(tab);
  scheduleAutoEngage(tab, 'button');
  scheduleAutoEngage(tab, 'keepers');
});

api.tabs.on.blur.add(function(tab) {
  log("#b8a", "[tabs.on.blur] %i %o", tab.id, tab)();
  ['button', 'keepers'].forEach(clearAutoEngageTimer.bind(null, tab));
});

api.tabs.on.loading.add(function(tab) {
  log("#b8a", "[tabs.on.loading] %i %o", tab.id, tab)();
  kifify(tab);
});

api.tabs.on.unload.add(function(tab, historyApi) {
  log("#b8a", "[tabs.on.unload] %i %o", tab.id, tab)();
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
  if (tabsTagging.length) {
    tabsTagging = tabsTagging.filter(idIsNot(tab.id));
  }
  ['button', 'keepers'].forEach(clearAutoEngageTimer.bind(null, tab));
  delete tab.nUri;
  delete tab.count;
  delete tab.engaged;
  delete tab.focusCallbacks;
  if (historyApi) {
    api.tabs.emit(tab, "reset");
  }
});

api.on.beforeSearch.add(throttle(function () {
  if (enabled('search')) {
    ajax('search', 'GET', '/search/warmUp');
  }
}, 50000));

var searchPrefetchCache = {};  // for searching before the results page is ready
var searchFilterCache = {};    // for restoring filter if user navigates back to results
api.on.search.add(function prefetchResults(query) {
  if (!me || !enabled('search')) return;
  log('[prefetchResults] prefetching for query:', query)();
  var entry = searchPrefetchCache[query];
  if (!entry) {
    entry = searchPrefetchCache[query] = {callbacks: [], response: null};
    searchOnServer({query: query, filter: (searchFilterCache[query] || {}).filter}, function (response) {
      entry.response = response;
      while (entry.callbacks.length) entry.callbacks.shift()(response);
      api.timers.clearTimeout(entry.expireTimeout);
      entry.expireTimeout = api.timers.setTimeout(cull, 10000);
    });
  } else {
    api.timers.clearTimeout(entry.expireTimeout);
  }
  var cull = cullSearchPrefetchCacheEntry.bind(null, query, entry);
  entry.expireTimeout = api.timers.setTimeout(cull, 10000);
});
function cullSearchPrefetchCacheEntry(key, val) {
  if (searchPrefetchCache[key] === val) {
    delete searchPrefetchCache[key];
  }
}

function getPrefetched(request, cb) {
  var cached = searchPrefetchCache[request.query];
  if (cached) {
    var logAndCb = function(r) {
      log('[getPrefetched] results:', r)();
      cb(r);
    };
    if (cached.response) {
      logAndCb(cached.response);
    } else {
      cached.callbacks.push(logAndCb);
    }
    return true;
  }
}

// ===== Local storage

function stored(key) {
  return api.storage[qualify(key)];
}

function store(key, value) {
  var qKey = qualify(key), prev = api.storage[qKey];
  if (value != null && prev !== String(value)) {
    log('[store] %s = %s (was %s)', key, value, prev)();
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
      var lz = global.LZ || require('./lzstring.min').LZ;
      return JSON.parse(lz.decompress(drafts));
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
      var lz = global.LZ || require('./lzstring.min').LZ;
      api.storage[qualify(key)] = lz.compress(drafts);
    }
  }
}

function saveDraft(key, draft) {
  log('[saveDraft]', key)();
  var now = draft.saved = Date.now();
  var drafts = loadDrafts();
  for (var k in drafts) {
    var d = drafts[k];
    if (now - d.saved > 259.2e6) { // 3 days
      log('[saveDraft] culling', k, d, Date(d.saved))();
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
      log('[discardDraft]', key, drafts[key])();
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
  log('[scheduleAutoEngage]', tab.id, type)();
  tab[timerName] = api.timers.setTimeout(function autoEngage() {
    delete tab[secName];
    delete tab[timerName];
    log('[autoEngage]', tab.id, type)();
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

function toFriendSearchResult(f) {
  return {
    id: f.id,
    name: f.name,
    pictureName: f.pictureName,
    parts: this.sf.splitOnMatches(this.q, f.name)
  };
}

function reTest(s) {
  return function (re) {return re.test(s)};
}
function hasId(id) {
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
function getThreadId(n) {
  return n.thread;
}
function idToThread(id) {
  return threadsById[id];
}

function devUriOr(uri) {
  return api.mode.isDev() ? 'http://dev.ezkeep.com:9000' : uri;
}
function apiUri(service) {
  return "https://" + (service === "" ? "api" : service) + ".kifi.com";
}
function serviceNameToUri(service) {
  switch (service) {
    case "eliza":
      return elizaBaseUri();
    case "search":
      return searchBaseUri();
    default:
      return apiBaseUri();
  }
}

var apiBaseUri = devUriOr.bind(0, apiUri(""));
var searchBaseUri = devUriOr.bind(0, apiUri("search"));
var elizaBaseUri = devUriOr.bind(0, apiUri("eliza"));

var webBaseUri = devUriOr.bind(0, "https://www.kifi.com");
var admBaseUri = devUriOr.bind(0, "https://admin.kifi.com");

function getFriends(next) {
  ajax('GET', '/ext/user/friends', function gotFriends(fr) {
    log('[gotFriends]', fr)();
    friends = fr;
    friendsById = {};
    for (var i = 0; i < fr.length; i++) {
      var f = standardizeUser(fr[i]);
      friendsById[f.id] = f;
    }
    friendSearchCache = null;
    if (next) next();
  });
}

function getTags(next) {
  ajax('GET', '/tags', function gotTags(arr) {
    log('[gotTags]', arr)();
    tags = arr;
    tagsById = tags.reduce(function(o, tag) {
      o[tag.id] = tag;
      return o;
    }, {});
    if (next) next();
  });
}

function getPrefs(next) {
  ajax('GET', '/ext/prefs', function gotPrefs(arr) {
    log('[gotPrefs]', arr)();
    if (me) {
      prefs = arr[1];
      eip = arr[2];
      socket.send(['eip', eip]);
    }
    if (next) next();
  });
}

function getRules(next) {
  ajax('GET', '/ext/pref/rules', {version: ruleSet.version}, function gotRules(o) {
    log('[gotRules]', o)();
    if (o && Object.getOwnPropertyNames(o).length > 0) {
      ruleSet = o.slider_rules;
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
};

// ===== Session management

var me, prefs, experiments, eip, socket, silence, onLoadingTemp;

function authenticate(callback, retryMs) {
  if (api.mode.isDev()) {
    openLogin(callback, retryMs);
  } else {
    startSession(callback, retryMs);
  }
}

function startSession(callback, retryMs) {
  ajax('POST', '/kifi/start', {
    installation: stored('installation_id'),
    version: api.version
  },
  function done(data) {
    log("[authenticate:done] reason: %s session: %o", api.loadReason, data)();
    unstore('logout');

    api.toggleLogging(data.experiments.indexOf('extension_logging') >= 0);
    me = standardizeUser(data.user);
    experiments = data.experiments;
    eip = data.eip;
    socket = socket || api.socket.open(
      elizaBaseUri().replace(/^http/, 'ws') + '/eliza/ext/ws?version=' + api.version + (eip ? '&eip=' + eip : ''),
      socketHandlers, onSocketConnect, onSocketDisconnect);
    logEvent.catchUp();
    mixpanel.catchUp();

    ruleSet = data.rules;
    urlPatterns = compilePatterns(data.patterns);
    store('installation_id', data.installationId);

    api.tabs.on.loading.remove(onLoadingTemp), onLoadingTemp = null;
    emitAllTabs('me_change', me);
    callback();
  },
  function fail(xhr) {
    log("[startSession:fail] xhr.status:", xhr.status)();
    if (!xhr.status || xhr.status >= 500) {  // server down or no network connection, so consider retrying
      if (retryMs) {
        api.timers.setTimeout(startSession.bind(null, callback, Math.min(60000, retryMs * 1.5)), retryMs);
      }
    } else if (stored('installation_id')) {
      openLogin(callback, retryMs);
    } else {
      api.tabs.selectOrOpen(webBaseUri() + '/');
      api.tabs.on.loading.add(onLoadingTemp = function(tab) {
        // if kifi.com home page, retry first authentication
        if (tab.url.replace(/\/(?:#.*)?$/, '') === webBaseUri()) {
          api.tabs.on.loading.remove(onLoadingTemp), onLoadingTemp = null;
          startSession(callback, retryMs);
        }
      });
    }
  });
}

function openLogin(callback, retryMs) {
  log("[openLogin]")();
  var baseUri = webBaseUri();
  api.popup.open({
    name: "kifi-authenticate",
    url: baseUri + '/login',
    width: 1020,
    height: 530}, {
    navigate: function(url) {
      if (url == baseUri + "/#_=_" || url == baseUri + "/") {
        log("[openLogin] closing popup")();
        this.close();
        startSession(callback, retryMs);
      }
    }});
}

function clearSession() {
  if (me) {
    unstore('drafts-' + me.id);
    api.tabs.each(function (tab) {
      api.icon.set(tab, 'icons/k_gray.png');
      api.tabs.emit(tab, 'me_change', null);
    });
  }
  me = prefs = experiments = eip = null;
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
  log("[deauthenticate]")();
  clearSession();
  store('logout', Date.now());
  api.popup.open({
    name: "kifi-deauthenticate",
    url: webBaseUri() + "/logout#_=_",
    width: 200,
    height: 100}, {
    navigate: function(url) {
      if (url == webBaseUri() + "/#_=_") {
        log("[deauthenticate] closing popup")();
        this.close();
      }
    }
  })
}

// ===== Main, executed upon install (or reinstall), update, re-enable, and browser start

api.timers.setTimeout(api.errors.wrap(function() {
  for(var a={},b=0;38>b;b++) // 38 = 42 - 42/10
    a[parseInt(" 0 5 611214041 h j s x1n3c3i3j3k3l3m3n6g6r6t6u6v6w6x6y6zcyczd0d1d2d3dgdhdkdl".substr(2*b,2),36).toString(2)]=" |_i(mMe/\\n\ngor.cy!W:ahst')V,v24Juwbdl".charAt(b);
  for(var d=[],b=0;263>b;b++) // lowest prime that is an irregular prime, an Eisenstein prime, a long prime, a Chen prime, a Gaussian prime, a happy prime, a sexy prime, a safe prime, and a Higgs prime. I think.
    d.push(("000"+parseInt("1b123ebe88bc92fc7b6f4fee9c5e582f36ec9b500550157b55cdb19b55cc355db01b6dbb534d9caf9dab6aaaadb8e27c4d3673b55a93be954abaaeaab9c7d9f69a4efa5ed75736d3ba8e6d79b74979b5734f9a6e6da7d8e88fcff8bda5ff2c3e00da6a1d6fd2c2ebfbf9f63c7f8fafc230f89618c7ffbc1aeda60eaf53c7e8081fd2000".charAt(b),16).toString(2)).substr(-4));
  for(var e=d.join(""),f="",g=0;g<e.length;) {
    for(var h="";!(h in a);)
      h+=e[g],g++;
    f+=a[h]
  }
  console.log('\n'+f);
}));

api.errors.wrap(authenticate.bind(null, function() {
  if (api.loadReason === 'install') {
    log('[main] fresh install')();
    var tab = api.tabs.anyAt(webBaseUri() + '/install');
    if (tab) {
      api.tabs.navigate(tab.id, webBaseUri() + '/');
    } else {
      api.tabs.open(webBaseUri() + '/');
    }
  }
  if (api.loadReason === 'install' || api.mode.isDev()) {
    store('prompt_to_import_bookmarks', true);
  }
}, 3000))();
