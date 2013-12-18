/*jshint globalstrict:true */
"use strict";

var api = api || require("./api");
var log = log || api.log;

var NOTIFICATION_BATCH_SIZE = 10;

//                          | sub |    |------- country domain -------|-- generic domain --|           |-- port? --|
var domainRe = /^https?:\/\/[^\/]*?((?:[^.\/]+\.[^.\/]{2,3}\.[^.\/]{2}|[^.\/]+\.[^.\/]{2,6}|localhost))(?::\d{2,5})?(?:$|\/)/;
var hostRe = /^https?:\/\/([^\/]+)/;

var tabsByUrl = {}; // by normalized url
var tabsByLocator = {};
var tabsTagging = [];
var notificationsCallbacks = [];
var threadDataCallbacks = {}; // by normalized url
var threadCallbacks = {}; // by thread ID

// ===== Cached data from server

var pageData = {}; // keyed by normalized url
var pageThreadData = {}; // keyed by normalized url
var messageData = {}; // keyed by thread id; todo: evict old threads from memory
var notifications;  // [] would mean user has none
var timeNotificationsLastSeen = new Date(0);
var numUnreadUnmutedThreads = 0;  // may include some not yet loaded
var haveAllNotifications;  // inferred
var friends = [];
var friendsById = {};
var ruleSet = {};
var urlPatterns = [];
var tags;  // [] means user has none
var tagsById;

function clearDataCache() {
  log("[clearDataCache]")();
  pageData = {};
  pageThreadData = {};
  messageData = {};
  notifications = null;
  timeNotificationsLastSeen = new Date(0);
  numUnreadUnmutedThreads = 0;
  haveAllNotifications = false;
  friends = [];
  friendsById = {};
  ruleSet = {};
  urlPatterns = [];
  tags = null;
  tagsById = null;
}

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


function ThreadData() {
}
ThreadData.prototype = {
  init: function (threads) {
    var oldReadTimes = this.threads ? this.threads.reduce(function(o, th) {
      o[th.id] = th.lastMessageRead;
      return o;
    }, {}) : {};
    threads.forEach(function(th) {
      th.participants = th.participants.filter(idIsNot(session.user.id));
      // avoid overwriting newer read-state information
      var oldTimeStr = oldReadTimes[th.id];
      if (oldTimeStr && new Date(oldTimeStr) > new Date(th.lastMessageRead || 0)) {
        th.lastMessageRead = oldTimeStr;
      }
    });
    this.threads = threads;
    delete this.stale;
  },
  getThread: function (threadId) {
    return this.threads.filter(hasId(threadId))[0];
  },
  addThread: function (th) {
    th.participants = th.participants.filter(idIsNot(session.user.id));
    var old = insertUpdateChronologically(this.threads, th, 'lastCommentedAt');
    if (old && old.lastMessageRead && new Date(old.lastMessageRead) > new Date(th.lastMessageRead || 0)) {
      th.lastMessageRead = old.lastMessageRead;
    }
  },
  getUnreadThreads: function() {
    var arr = [];
    for (var i = this.threads.length; i--;) {
      var th = this.threads[i];
      var readAt = th.lastMessageRead;
      if (!readAt || new Date(readAt) < new Date(th.lastCommentedAt)) {
        arr.push(th);
      }
    }
    return arr;
  },
  countUnreadThreads: function() {
    return this.getUnreadThreads().length;
  },
  getUnreadLocator: function () {
    var unread = this.getUnreadThreads();
    return unread.length === 1 ? '/messages/' + unread[0].id : (unread.length ? '/messages' : null);
  },
  markRead: function (threadId, timeStr) {
    var th = this.getThread(threadId);
    if (th && (!th.lastMessageRead || new Date(timeStr) > new Date(th.lastMessageRead))) {
      th.lastMessageRead = timeStr;
      return true;
    }
  }
};

function insertUpdateChronologically(arr, o, time) {
  var date = new Date(o[time]), old;
  for (var i = arr.length; i--;) {
    var el = arr[i];
    if (date > new Date(el[time])) {
      arr.splice(i + 1, 0, o);
      date = null;
    }
    if (o.id === el.id) {
      arr.splice(i, 1);
      old = el;
    }
  }
  if (date) {
    arr.unshift(o);
  }
  return old;
}

// ===== Server requests

function ajax(service, method, uri, data, done, fail) {  // method and uri are required
  if (service.match(/^(?:GET|POST|HEAD|OPTIONS|PUT)$/)) { // shift args if service is missing
    fail = done, done = data, data = uri, uri = method, method = service, service = 'api';
  }
  if (typeof data == "function") {  // shift args if data is missing and done is present
    fail = done, done = data, data = null;
  }

  if (data && method.match(/^(?:GET|HEAD)$/)) {
    var a = [];
    for (var key in data) {
      if (data.hasOwnProperty(key)) {
        var val = data[key];
        if (val != null) {
          a.push(encodeURIComponent(key) + "=" + encodeURIComponent(val));
        }
      }
    }
    uri += (~uri.indexOf("?") ? "&" : "?") + a.join("&").replace(/%20/g, "+");
    data = null;
  }

  api.request(method, serviceNameToUri(service) + uri, data, done, fail);
}

// ===== Event logging

var mixpanel = {
  enabled: true,
  queue: [],
  batch: [],
  sendBatch: function(){
    if (this.batch.length > 0) {
      var json = JSON.stringify(this.batch);
      var dataString = "data=" + api.util.btoa(unescape(encodeURIComponent(json)));
      api.postRawAsForm("https://api.mixpanel.com/track/", dataString);
      this.batch.length = 0;
    }
  },
  augmentAndBatch: function(data) {
    data.properties.token = 'cff752ff16ee39eda30ae01bb6fa3bd6';
    data.properties.distinct_id = session.user.id;
    data.properties.browser = api.browser.name;
    data.properties.browserDetails = api.browser.userAgent;
    this.batch.push(data);
    if (this.batch.length > 10) {
      this.sendBatch();
    }
  },
  track: function(eventName, properties) {
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
      if (session) {
        this.augmentAndBatch(data);
      } else {
        this.queue.push(data);
      }
    }
  },
  catchUp: function() {
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
    installId: getStored("kifi_installation_id"), // ExternalId[KifiInstallation]
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

function forEachThreadWithThreadId(threadId, fn, that) {
  Object.keys(pageThreadData).forEach(function (nUrl) {
    var threadData = this[nUrl],
      threads = threadData.threads;
    if (threads) {
      threads.forEach(function (thread) {
        if (thread.id === threadId) {
          fn.call(that, thread, threadId, threads);
        }
      });
    }
  }, pageThreadData);
}

function forEachTabWithThreadId(threadId, fn, that) {
  forEachThreadWithThreadId(threadId, function (thread) {
    var tabs = tabsByUrl[thread.nUrl];
    if (tabs) {
      for (var i = 0, len = tabs.length, tab; i < len; i++) {
        fn.call(this, tabs[i], thread, threadId, tabs);
      }
    }
  }, that);
}

// ===== WebSocket handlers

var socketHandlers = {
  denied: function() {
    log("[socket:denied]")();
    clearSession();
  },
  version: function(v) {
    log("[socket:version]", v)();
    if (api.version != v) {
      api.requestUpdateCheck();
    }
  },
  experiments: function(exp) {
    log("[socket:experiments]", exp)();
    session.experiments = exp;
    api.toggleLogging(exp.indexOf('extension_logging') >= 0);
  },
  new_friends: function(fr) {
    log("[socket:new_friends]", fr)();
    for (var i = 0; i < fr.length; i++) {
      var f = fr[i];
      if (friendsById[f.id]) {
        friends = friends.filter(function(e) {return e.id != f.id})
      }
      friends.push(f)
      friendsById[f.id] = f;
    }
  },
  create_tag: onTagChangeFromServer.bind(null, 'create'),
  rename_tag: onTagChangeFromServer.bind(null, 'rename'),
  remove_tag: onTagChangeFromServer.bind(null, 'remove'),
  thread_participants: function(threadId, participants) {
    log("[socket:thread_participants]", threadId, participants)();
    participants = participants.filter(idIsNot(session.user.id));
    forEachTabWithThreadId(threadId, function (tab, thread) {
      thread.participants = participants;
      api.tabs.emit(tab, 'participants', participants);
    });
  },
  thread_muted: function(threadId, muted) {
    log("[socket:thread_muted]", threadId, muted)();
    forEachTabWithThreadId(threadId, function (tab, thread) {
      thread.muted = muted;
      api.tabs.emit(tab, 'muted', muted);
    });
  },
  url_patterns: function(patterns) {
    log("[socket:url_patterns]", patterns)();
    urlPatterns = compilePatterns(patterns);
  },
  notifications: function(arr, numUnreadUnmuted) {  // initial load of notifications
    log("[socket:notifications]", arr, numUnreadUnmuted)();
    if (!notifications) {
      for (var i = 0; i < arr.length; i++) {
        standardizeNotification(arr[i]);
      }
      notifications = arr;
      haveAllNotifications = arr.length < NOTIFICATION_BATCH_SIZE;
      numUnreadUnmutedThreads = numUnreadUnmuted;
      while (notificationsCallbacks.length) {
        notificationsCallbacks.shift()();
      }
      tellVisibleTabsNoticeCountIfChanged();
    }
  },
  notification: function(n) {  // a new notification (real-time)
    log("[socket:notification]", n)();
    standardizeNotification(n);
    if (insertNewNotification(n)) {
      sendNotificationToTabs(n);
    }
  },
  missed_notifications: function(arr, serverTime) {
    log("[socket:missed_notifications]", arr, serverTime)();
    var threadIds = {};
    for (var i = arr.length; i--;) {
      var n = arr[i];
      standardizeNotification(n);

      if (!insertNewNotification(n)) {
        arr.splice(i, 1);
      } else if ((new Date(serverTime) - new Date(notifications[0].time)) < 1000*60) {
        sendNotificationToTabs(n);
      }

      if (n.thread) {
        threadIds[n.thread] = n.url;
      }
    }
    if (arr.length) {
      forEachTabAtLocator('/notices', function(tab) {
        api.tabs.emit(tab, "missed_notifications", arr);
      });
      tellVisibleTabsNoticeCountIfChanged();
    }
    for (var id in threadIds) {
      var nUri = threadIds[id];
      var td = pageThreadData[nUri];
      if (td) {
        socket.send(["get_thread_info", id], td.addThread.bind(td));  // TODO: update open views in callback
      }
      if (messageData[id] && !threadCallbacks[id]) {
        socket.send(["get_thread", id]);  // TODO: "get_thread_since" (don't need messages already loaded)
        threadCallbacks[id] = [];  // TODO: add callback that updates open views
      }
    }
  },
  last_notify_read_time: function(t) {
    log("[socket:last_notify_read_time]", t)();
    var time = new Date(t);
    if (time > timeNotificationsLastSeen) {
      timeNotificationsLastSeen = time;
    }
  },
  all_notifications_visited: function(id, time) {
    log("[socket:all_notifications_visited]", id, time)();
    markAllNoticesVisited(id, time);
  },
  thread: function(th) {
    log("[socket:thread]", th)();
    messageData[th.id] = th.messages;
    for (var arr = threadCallbacks[th.id]; arr && arr.length;) {
      arr.shift()({id: th.id, messages: th.messages});
    }
    delete threadCallbacks[th.id];
  },
  message: function(threadId, message) {
    log("[socket:message]", threadId, message, message.nUrl)();
    forEachTabAtLocator("/messages/" + threadId, function(tab) {
      api.tabs.emit(tab, "message", {threadId: threadId, message: message, userId: session.user.id});
    });
    var messages = messageData[threadId];
    if (messages) {
      insertUpdateChronologically(messages, message, "createdAt");
    }
    var td = pageThreadData[message.nUrl] || pageThreadData[message.url];
    if (td) {
      var thread = td.getThread(threadId);
      if (thread) {
        // until we have the updated threadinfo, do a best attempt at updating the thread
        var m = messages && messages[messages.length - 1] || message;
        thread.digest = m.text;
        thread.lastAuthor = m.user.id;
        thread.lastCommentedAt = m.createdAt;
        thread.messageCount = messages ? messages.length : (thread.messageCount + 1);
        thread.messageTimes[message.id] = message.createdAt;
        //thread.participants = lastMessage.participants.filter(idIsNot(session.user.id)); // not yet needed
        withThread(thread);
      } else if (td) {
        // this is probably the first message of a new thread
        socket.send(["get_thread_info", threadId], withThread);
      }
    }
    function withThread(th) {
      td.addThread(th);
      if (message.user.id === session.user.id) {
        td.markRead(th.id, message.createdAt);
      }
      forEachTabAtUriAndLocator(message.url, message.nUrl, "/messages", function(tab) {
        api.tabs.emit(tab, "thread_info", th);
      });
      tellTabsUnreadThreadCountIfChanged(td, message.nUrl, message.url);
    }
  },
  message_read: function(nUri, threadId, time, messageId) {
    log("[socket:message_read]", nUri, threadId, time)();
    removeNotificationPopups(threadId);
    markNoticesVisited("message", messageId, time, "/messages/" + threadId);
    for (var uri in pageThreadData) {
      var td = pageThreadData[uri];
      var thread = td.getThread(threadId);
      if (thread && td.markRead(threadId, time)) {
        forEachTabAt(uri, function(tab) {
          api.tabs.emit(tab, "thread_info", thread);
        });
        tellTabsUnreadThreadCountIfChanged(td, uri);
      }
    }
    tellVisibleTabsNoticeCountIfChanged();
  },
  unread_notifications_count: function(count) {
    // see comment in syncNumUnreadUnmutedThreads() :(
    if (numUnreadUnmutedThreads !== count) {
      if (notifications) {
        socket.send(["get_missed_notifications", notifications.length ? notifications[0].time : new Date(0).toISOString()]);
        reportError("numUnreadUnmutedThreads count incorrect: " + numUnreadUnmutedThreads + " != " + count);
      }
      numUnreadUnmutedThreads = count;
      tellVisibleTabsNoticeCountIfChanged();
    }
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
    ajax("POST", "/bookmarks/private", data, function(o) {
      log("[setPrivate] response:", o)();
    });
    forEachTabAt(tab.url, tab.nUri, function(tab) {
      api.tabs.emit(tab, "kept", {kept: data.private ? "private" : "public"});
    });
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
  set_keeper_pos: function(o, _, tab) {
    for (var nUri in pageData) {
      if (nUri.match(hostRe)[1] == o.host) {
        pageData[nUri].position = o.pos;
      }
    }
    ajax("POST", "/ext/pref/keeperPosition", {host: o.host, pos: o.pos});
  },
  set_enter_to_send: function(data) {
    session.prefs.enterToSend = data;
    ajax("POST", "/ext/pref/enterToSend?enterToSend=" + data);
  },
  useful_page: function(o, _, tab) {
    ajax('search', 'POST', '/search/events/browsed', [tab.url]);
  },
  log_event: function(data) {
    logEvent.apply(null, data);
  },
  log_search_event: function(data) {
    var whichEvent = data[0];
    ajax("search", "POST", "/search/events/" + whichEvent, data[1]);
  },
  send_message: function(data, respond, tab) {
    var nUri = tab.nUri || data.url;
    data.extVersion = api.version;
    ajax("eliza", "POST", "/eliza/messages", data, function(o) {
      log("[send_message] resp:", o)();

      var thread = o.threadInfo;
      var td = pageThreadData[nUri];
      if (td) {
        td.addThread(thread);
        td.markRead(thread.id, o.messages[o.messages.length - 1].createdAt);
      }
      messageData[thread.id] = o.messages;
      respond(o);
    });
  },
  send_reply: function(data, respond) {
    var id = data.threadId;
    delete data.threadId;
    data.extVersion = api.version;
    ajax("eliza", "POST", "/eliza/messages/" + id, data, logAndRespond, logErrorAndRespond);
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
      var unreadNotification;
      for (var i = 0; i < notifications.length; i++) {
        var n = notifications[i];
        if (n.unread && (n.thread === o.threadId || n.id === o.messageId)) {
          unreadNotification = true;
          o.messageId = n.id;
        }
      }

      var td = pageThreadData[tab.nUri];
      if (o.forceSend || unreadNotification || !td || td.markRead(o.threadId, o.time)) {
        markNoticesVisited("message", o.messageId, o.time, "/messages/" + o.threadId);
        if (td) {
          tellTabsUnreadThreadCountIfChanged(td, tab.nUri);
        }
        tellVisibleTabsNoticeCountIfChanged();
        socket.send(["set_message_read", o.messageId]);
      }
    });
  },
  participants: function(id, respond, tab) {
    var td = pageThreadData[tab.nUri];
    var th = td && td.getThread(id);
    if (th) {
      respond(th.participants);
    } else {
      var msgs = messageData[id];
      if (msgs) {
        reply({messages: msgs});
      } else {
        var tc = threadCallbacks[id];
        if (tc) {
          tc.push(reply);
        } else {
          socket.send(["get_thread", id]);
          threadCallbacks[id] = [reply];
        }
      }
    }
    function reply(th) {
      respond(th.messages[0].participants.filter(idIsNot(session.user.id)));
    }
  },
  set_global_read: function(o, _, tab) {
    markNoticesVisited("global", o.noticeId);
    tellVisibleTabsNoticeCountIfChanged();
    socket.send(["set_global_read", o.noticeId]);
  },
  threads: function(_, respond, tab) {
    var td = pageThreadData[tab.nUri];
    if (td) {
      reply(td);
    } else {
      (threadDataCallbacks[tab.nUri] || (threadDataCallbacks[tab.nUri] = [])).push(reply);
    }
    function reply(td) {
      respond(td.threads);
    }
  },
  thread: function(data, respond, tab) {
    var msgs = messageData[data.id];
    if (msgs) {
      if (data.respond) {
        respond({id: data.id, messages: msgs});
      }
    } else {
      var tc = threadCallbacks[data.id];
      if (tc) {
        if (data.respond) {
          tc.push(respond);
        }
      } else {
        socket.send(["get_thread", data.id]);
        threadCallbacks[data.id] = data.respond ? [respond] : [];
      }
    }
  },
  notifications: function(_, respond) {
    if (notifications) {
      reply();
    } else {
      notificationsCallbacks.push(reply);
    }
    function reply() {
      syncNumUnreadUnmutedThreads(); // sanity checking
      respond({
        notifications: notifications.slice(0, NOTIFICATION_BATCH_SIZE),
        timeLastSeen: timeNotificationsLastSeen.toISOString(),
        numNotVisited: numUnreadUnmutedThreads});
    }
  },
  old_notifications: function(timeStr, respond) {
    var time = new Date(timeStr);
    var n = notifications.length, oldest = notifications[n-1];
    if (new Date(oldest.time) < time) {
      for (var i = n - 1; i && new Date(notifications[i-1].time) < time; i--);
      respond(notifications.slice(i, i + NOTIFICATION_BATCH_SIZE));
    } else if (haveAllNotifications) {
      respond([]);
    } else {
      socket.send(["get_old_notifications", timeStr, NOTIFICATION_BATCH_SIZE], function(arr) {
        for (var i = 0; i < arr.length; i++) {
          standardizeNotification(arr[i]);
        }
        if (notifications[notifications.length - 1] === oldest) {
          notifications.push.apply(notifications, arr);
          if (arr.length < NOTIFICATION_BATCH_SIZE) {
            haveAllNotifications = true;
          }
        }
        respond(arr);
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
    if (o.new) {
      var fragments = o.new.split("/");
      mixpanel.track("user_viewed_pane", {'type': fragments.length>1 ? fragments[1] : o.new});
      var arr = tabsByLocator[o.new];
      if (arr) {
        arr = arr.filter(idIsNot(tab.id));
        arr.push(tab);
      }
      tabsByLocator[o.new] = arr || [tab];
    }
  },
  notifications_read: function(t) {
    var time = new Date(t);
    if (time > timeNotificationsLastSeen) {
      timeNotificationsLastSeen = time;
      socket.send(["set_last_notify_read_time", t]);
    }
  },
  all_notifications_visited: function(o) {
    markAllNoticesVisited(o.id, o.time);
    socket.send(["set_all_notifications_visited", o.id]);
  },
  session: function(_, respond) {
    respond(session);
  },
  web_base_uri: function(_, respond) {
    respond(webBaseUri());
  },
  get_friends: function(_, respond) {
    respond(friends);
  },
  get_networks: function(friendId, respond) {
    socket.send(["get_networks", friendId], respond);
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
        api.tabs.open(link.nUri, function(tabId) {
          awaitDeepLink(link, tabId);
        });
      }
    }
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
  remove_notification: function(o) {
    removeNotificationPopups(o.associatedId);
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
  add_participants: function(data, respond, tab) {
    var threadId = data.threadId,
      userIds = data.userIds;
    socket.send(['add_participants_to_thread', threadId, userIds]);
  },
  is_muted: function(threadId, respond, tab) {
    var td = pageThreadData[tab.nUri],
      th = td && td.getThread(threadId);
    respond({
      success: Boolean(th),
      response: Boolean(th && th.muted)
    });
  },
  mute_thread: function(threadId, respond, tab) {
    socket.send(['mute_thread', threadId]);
  },
  unmute_thread: function(threadId, respond, tab) {
    socket.send(['unmute_thread', threadId]);
  },
  get_bookmark_count_if_should_import: function(_, respond) {
    if (getStored('prompt_to_import_bookmarks')) {
      api.bookmarks.getAll(function(bms) {
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
  report_error: function(data, _, tag) {
    // TODO: filter errors and improve fidelity/completeness of information
    //reportError(data.message, data.url, data.lineNo);
  }
});

function onTagChangeFromServer(op, tag) {
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
  tabsTagging.forEach(function(tab) {
    api.tabs.emit(tab, 'tag_change', this);
  }, {op: op, tag: tag});
}

function removeNotificationPopups(associatedId) {
  emitAllTabs("remove_notification", associatedId);
}

function standardizeNotification(n) {
  n.category = (n.category || "message").toLowerCase();
  n.unread = n.unread || (n.unreadAuthors > 0);
  for (var i = n.participants ? n.participants.length : 0; i--;) {
    if (n.participants[i].id == session.user.id) {
      n.participants.splice(i, 1);
    }
  }
}

function sendNotificationToTabs(n) {
  var told = {};
  api.tabs.eachSelected(tellTab);
  forEachTabAtLocator('/notices', tellTab);
  tellVisibleTabsNoticeCountIfChanged();

  if (n.unread && !n.muted) {
    api.play("media/notification.mp3");
  }

  function tellTab(tab) {
    if (told[tab.id]) return;
    told[tab.id] = true;
    api.tabs.emit(tab, "new_notification", n, {queue: true});
  }
}

function insertNewNotification(n) {
  if (!notifications) return false;
  var time = new Date(n.time);
  for (var i = 0; i < notifications.length; i++) {
    if (new Date(notifications[i].time) <= time) {
      if (notifications[i].id == n.id) {
        return false;
      }
      break;
    } else if ((n.thread && notifications[i].thread == n.thread) || (notifications[i].id == n.id)) {
      // there is already a more recent notification for this thread
      return false;
    }
  }
  notifications.splice(i, 0, n);

  if (n.unread && !n.muted) {  // may have been visited before arrival
    var td, th;
    if (n.category === 'message' &&
        (td = pageThreadData[n.url]) &&
        (th = td.getThread(n.locator.substr(10))) &&
        new Date(th.lastMessageRead || 0) > new Date(n.time)) {
      n.unread = false;
      n.unreadAuthors = 0;
    } else {
      numUnreadUnmutedThreads++;
    }
  }

  while (++i < notifications.length) {
    var n2 = notifications[i];
    if ((n.thread && n2.thread == n.thread) || (n.id == n2.id)) {
      notifications.splice(i--, 1);
      if (n2.unread && !n2.muted) {
        decrementNumUnreadUnmutedThreads(n2);
      }
    }
  }

  return true;
}

function syncNumUnreadUnmutedThreads() {
  // We have an open issue where numUnreadUnmutedThreads gets off - it goes below 0
  // So either an increment is not happening, or a decrement is happening too often.
  // The issue goes back several months (with the -1 notification issue), but has gotten
  // much worse lately. I've had difficulty consistantly reproducing, so am adding this
  // sync in until we can identify the real issue counts get off. Could be related to
  // spotty internet, or some logic error above. -Andrew
  if (socket) {
    socket.send(["get_unread_notifications_count"]);
  }
}

// id is of last read message, timeStr is its createdAt time (not notification's).
// If category is global, we do not check the timeStr, and locator because id identifies
// it sufficiently. `undefined` can be passed in for everything but category and id.
function markNoticesVisited(category, id, timeStr, locator) {
  var time = timeStr ? new Date(timeStr) : null;
  notifications && notifications.forEach(function(n, i) {
    if ((!locator || n.locator == locator) &&
        (n.id == id || new Date(n.time) <= time)) {
      if (n.unread) {
        n.unread = false;
        n.unreadAuthors = 0;
        decrementNumUnreadUnmutedThreads(n);
      }
    }
  });
  forEachTabAtLocator('/notices', function(tab) {
    api.tabs.emit(tab, "notifications_visited", {
      category: category,
      time: timeStr,
      locator: locator,
      id: id,
      numNotVisited: numUnreadUnmutedThreads});
  });
}

function markAllNoticesVisited(id, timeStr) {  // id and time of most recent notification to mark
  var time = new Date(timeStr);
  for (var i = 0; i < notifications.length; i++) {
    var n = notifications[i];
    if (n.unread && (n.id === id || new Date(n.time) <= time)) {
      n.unread = false;
      n.unreadAuthors = 0;
      if (n.category === 'message') {
        var td = pageThreadData[n.url];
        if (td && td.markRead(n.locator.substr(10), n.time)) {
          tellTabsUnreadThreadCountIfChanged(td, n.url);
        }
      }
    }
  }
  numUnreadUnmutedThreads = notifications.filter(function(n) {
    return n.unread && !n.muted;
  }).length;
  forEachTabAtLocator('/notices', function(tab) {
    api.tabs.emit(tab, "all_notifications_visited", {
      id: id,
      time: timeStr,
      numNotVisited: numUnreadUnmutedThreads});
  });

  tellVisibleTabsNoticeCountIfChanged();
}

function decrementNumUnreadUnmutedThreads(n) {
  if (numUnreadUnmutedThreads <= 0) {
    log("#a00", "[decrementNumUnreadUnmutedThreads] error", numUnreadUnmutedThreads, n)();
  }
  if (numUnreadUnmutedThreads > 0 || ~session.experiments.indexOf("admin")) {  // exposing -1 to admins to help debug
    numUnreadUnmutedThreads--;
  }
}

function awaitDeepLink(link, tabId, retrySec) {
  if (link.locator) {
    var tab = api.tabs.get(tabId);
    if (tab && (link.url || link.nUri).match(domainRe)[1] == (tab.nUri || tab.url).match(domainRe)[1]) {
      log("[awaitDeepLink]", tabId, link)();
      api.tabs.emit(tab, "open_to", {
        trigger: "deepLink",
        locator: link.locator,
        redirected: (link.url || link.nUri) !== (tab.nUri || tab.url)
      }, {queue: 1});
    } else if ((retrySec = retrySec || .5) < 5) {
      log("[awaitDeepLink]", tabId, "retrying in", retrySec, "sec")();
      api.timers.setTimeout(awaitDeepLink.bind(null, link, tabId, retrySec + .5), retrySec * 1000);
    }
  } else {
    log("[awaitDeepLink] no locator", tabId, link)();
  }
}

function dateWithoutMs(t) { // until db has ms precision
  var d = new Date(t);
  d.setMilliseconds(0);
  return d;
}

function forEachTabAt() { // (url[, url]..., f)
  var done = {};
  var f = arguments[arguments.length - 1];
  for (var i = arguments.length - 1; i--;) {
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
  api.tabs.eachSelected(function(tab) {
    if (tab.counts && tab.counts.n !== numUnreadUnmutedThreads) {
      tab.counts.n = numUnreadUnmutedThreads;
      api.tabs.emit(tab, "counts", tab.counts, {queue: 1});
    }
  });
}

function tellTabsUnreadThreadCountIfChanged(td) { // (td, url[, url]...)
  var m = td.countUnreadThreads();
  var args = Array.prototype.slice.call(arguments, 1);
  args.push(function(tab) {
    if (tab.counts.m !== m) {
      tab.counts.m = m;
      tab.counts.n = numUnreadUnmutedThreads;
      api.tabs.emit(tab, "counts", tab.counts, {queue: 1});
    }
  });
  forEachTabAt.apply(null, args);
}


var DEFAULT_RES = 5;
var MAX_RES_FOR_NEW = 2;
var TWO_WEEKS = 1000 * 60 * 60 * 24 * 7 * 2;
function getSearchMaxResults(request) {
  if (request.lastUUID) {
    return DEFAULT_RES;
  }

  var pref = api.prefs.get("maxResults");
  if (pref !== DEFAULT_RES) {
    return pref;
  }

  var joined = session.joined;
  if (joined && (Date.now() - joined) < TWO_WEEKS) {
    return MAX_RES_FOR_NEW;
  }

  return DEFAULT_RES;
}

function searchOnServer(request, respond) {
  if (request.first && getPrefetched(request, respond)) return;

  if (!session) {
    log("[searchOnServer] no session")();
    respond({});
    return;
  }

  if (request.filter) {
    searchFilterCache[request.query] = {filter: request.filter, time: Date.now()};  // TODO: purge cache
  } else {
    delete searchFilterCache[request.query];
  }

  var when, params = {
      q: request.query,
      f: request.filter && request.filter.who,
      maxHits: getSearchMaxResults(request),
      lastUUID: request.lastUUID,
      context: request.context,
      kifiVersion: api.version};
  if (when = request.filter && request.filter.when) {
    var d = new Date();
    params.tz = d.toTimeString().substr(12, 5);
    params.start = ymd(new Date(d - {t:0, y:1, w:7, m:30}[when] * 86400000));
    if (when == "y") {
      params.end = params.start;
    }
  }

  var respHandler = function(resp) {
      log("[searchOnServer] response:", resp)();
      resp.filter = request.filter;
      resp.session = session;
      resp.admBaseUri = admBaseUri();
      resp.showScores = api.prefs.get("showScores");
      resp.hits.forEach(function(hit){
        hit.uuid = resp.uuid;
      });
      respond(resp);
  };

  if (session.experiments.indexOf('tsearch') < 0) {
    ajax("search", "GET", "/search", params, respHandler);
  } else {
    ajax("api", "GET", "/tsearch", params, respHandler);
  }
  return true;
}

function ymd(d) {  // yyyy-mm-dd local date
  return new Date(d - d.getTimezoneOffset() * 60000).toISOString().substr(0, 10);
}

// kifi icon in location bar
api.icon.on.click.add(function(tab) {
  api.tabs.emit(tab, "button_click", null, {queue: 1});
});

function kifify(tab) {
  log("[kifify]", tab.id, tab.url, tab.icon || '', tab.nUri || '', session ? '' : 'no session')();
  if (!tab.icon) {
    api.icon.set(tab, "icons/keep.faint.png");
  }

  if (!session) {
    if (!getStored('logout') || tab.url.indexOf(webBaseUri()) === 0) {
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

  var url = tab.url;
  var uri = tab.nUri || url;

  // page data
  var d = pageData[uri];
  if (d) {
    if (!tab.nUri) {
      stashTab(tab, uri);
      kififyWithPageData(tab, d);
    }
  } else {
    ajax("POST", "/ext/pageDetails", {url: url}, gotPageDetailsFor.bind(null, url, tab), function fail(xhr) {
      if (xhr.status === 403) {
        clearSession();
      }
    });
  }

  // thread data
  var td = pageThreadData[uri];
  if (td && !td.stale) {
    tab.counts = {n: numUnreadUnmutedThreads, m: td.countUnreadThreads()};
    api.tabs.emit(tab, "counts", tab.counts, {queue: 1});
  } else {
    socket.send(["get_threads", url], gotThreadDataFor.bind(null, url, tab));
  }
}

function stashTab(tab, uri) {
  tab.nUri = uri;
  var tabs = tabsByUrl[uri];
  if (tabs) {
    for (var i = tabs.length; i--;) {
      if (tabs[i].id == tab.id) {
        tabs.splice(i, 1);
      }
    }
    tabs.push(tab);
  } else {
    tabsByUrl[uri] = [tab];
  }
  log("[stashTab]", tab.id)();
}

function kififyWithPageData(tab, d) {
  log("[kififyWithPageData]", tab.id, tab.engaged ? 'already engaged' : '')();
  setIcon(tab, d.kept);

  api.tabs.emit(tab, "init", {  // harmless if sent to same page more than once
    kept: d.kept,
    position: d.position,
    hide: d.neverOnSite || ruleSet.rules.sensitive && d.sensitive,
    tags: d.tags
  }, {queue: 1});

  // consider triggering automatic keeper behavior on page to engage user (only once)
  if (!tab.engaged) {
    tab.engaged = true;
    if (!d.kept && !d.neverOnSite && (!d.sensitive || !ruleSet.rules.sensitive)) {
      if (ruleSet.rules.url && urlPatterns.some(reTest(tab.url))) {
        log("[initTab]", tab.id, "restricted")();
      } else if (ruleSet.rules.shown && d.shown) {
        log("[initTab]", tab.id, "shown before")();
      } else {
        if (api.prefs.get("showSlider") && ruleSet.rules.scroll) {
          api.tabs.emit(tab, "scroll_rule", ruleSet.rules.scroll, {queue: 1});
        }
        tab.autoShowSec = (ruleSet.rules.focus || [])[0];
        if (tab.autoShowSec != null && api.tabs.isFocused(tab)) {
          scheduleAutoShow(tab);
        }
        if (d.keepers.length) {
          api.tabs.emit(tab, "keepers", {keepers: d.keepers, otherKeeps: d.otherKeeps}, {queue: 1});
        }
      }
    }
  }
}

function gotPageDetailsFor(url, tab, resp) {
  var tabIsOld = api.tabs.get(tab.id) !== tab || url.split('#', 1)[0] !== tab.url.split('#', 1)[0];

  log("[gotPageDetailsFor]", tab.id, tabIsOld ? 'OLD' : '', url, resp)();

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
  d.otherKeeps = d.keeps - d.keepers.length - (d.kept === "public");

  if (!tabIsOld) {
    pageData[nUri] = d;
    stashTab(tab, nUri);
    kififyWithPageData(tab, d);
  }
}

function gotThreadDataFor(url, tab, threads, nUri) {
  var tabIsOld = api.tabs.get(tab.id) !== tab || url.split('#', 1)[0] !== tab.url.split('#', 1)[0];
  log("[gotThreadDataFor]", tab.id, tabIsOld ? 'OLD' : '', url, nUri === url ? '' : nUri, threads)();

  var td = pageThreadData[nUri] || pageThreadData[threads.length ? threads[0].nUrl : ''] || new ThreadData;
  var oldMessageCounts = td.threads && td.threads.reduce(function(o, th) {
    o[th.id] = th.messageCount;
    return o;
  }, {});

  td.init(threads);

  if (!tabIsOld) {
    pageThreadData[nUri] = td;

    tab.counts = {n: numUnreadUnmutedThreads, m: td.countUnreadThreads()};
    api.tabs.emit(tab, "counts", tab.counts, {queue: 1});

    if (ruleSet.rules.message && tab.counts.m) {  // open immediately to unread message(s)
      api.tabs.emit(tab, "open_to", {trigger: "message", locator: td.getUnreadLocator()}, {queue: 1});
      td.getUnreadThreads().forEach(function(th) {
        socket.send(["get_thread", th.id]);  // prefetch
        if (!threadCallbacks[th.id]) {
          threadCallbacks[th.id] = [];
        }
      });
    }
  }

  for (var callbacks = threadDataCallbacks[nUri]; callbacks && callbacks.length;) {
    callbacks.shift()(td);
  }
  delete threadDataCallbacks[nUri];

  if (oldMessageCounts) {
    tellTabsUnreadThreadCountIfChanged(td, nUri);

    // Push threads with any new messages to relevant tabs
    var threadsWithNewMessages = [];
    td.threads.forEach(function(th) {
      var oldMessageCount = oldMessageCounts[th.id] || 0;
      if (oldMessageCount < th.messageCount) {
        threadsWithNewMessages.push(th);
        if (threadCallbacks[th.id]) {
          threadCallbacks[th.id].push(tellTabs);
        } else {
          socket.send(['get_thread', th.id]);
          threadCallbacks[th.id] = [tellTabs];
        }
      }
      function tellTabs(o) {
        forEachTabAtLocator('/messages/' + o.id, function(tab) {
          if (o.messages.length - oldMessageCount === 1) {
            api.tabs.emit(tab, 'message', {
              threadId: o.id,
              message: o.messages[o.messages.length - 1],
              userId: session.user.id});
          } else {
            api.tabs.emit(tab, 'thread', {id: o.id, messages: o.messages, userId: session.user.id});
          }
        });
      }
    });
    if (threadsWithNewMessages.length === 1) {  // special cased to trigger an animation
      var th = threadsWithNewMessages[0];
      forEachTabAtUriAndLocator(nUri, '/messages', function(tab) {
        api.tabs.emit(tab, 'thread_info', th);
      });
    } else if (threadsWithNewMessages.length > 1) {
      forEachTabAtUriAndLocator(nUri, '/messages', function(tab) {
        api.tabs.emit(tab, 'threads', td.threads);
      });
    }
  }
}

function setIcon(tab, kept) {
  log("[setIcon] tab:", tab.id, "kept:", kept)();
  api.icon.set(tab, kept ? "icons/kept.png" : "icons/keep.png");
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

api.tabs.on.focus.add(function(tab) {
  log("#b8a", "[tabs.on.focus] %i %o", tab.id, tab)();

  for (var key in tab.focusCallbacks) {
    tab.focusCallbacks[key](tab);
  }

  delete tab.focusCallbacks;
  kifify(tab);
  if (tab.autoShowSec != null && !tab.autoShowTimer) {
    scheduleAutoShow(tab);
  }
});

api.tabs.on.blur.add(function(tab) {
  log("#b8a", "[tabs.on.blur] %i %o", tab.id, tab)();
  api.timers.clearTimeout(tab.autoShowTimer);
  delete tab.autoShowTimer;
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
    delete pageThreadData[tab.nUri];
  }
  for (var loc in tabsByLocator) {
    var tabs = tabsByLocator[loc];
    for (var i = tabs.length; i--;) {
      if (tabs[i] === tab) {
        tabs.splice(i, 1);
      }
    }
    if (!tabs.length) {
      delete tabsByLocator[loc];
    }
  }
  if (tabsTagging.length) {
    tabsTagging = tabsTagging.filter(idIsNot(tab.id));
  }
  api.timers.clearTimeout(tab.autoShowTimer);
  delete tab.autoShowTimer;
  delete tab.nUri;
  delete tab.counts;
  delete tab.engaged;
  delete tab.focusCallbacks;
  if (historyApi) {
    api.tabs.emit(tab, "reset");
  }
});

api.on.beforeSearch.add(throttle(function () {
  if (session && ~session.experiments.indexOf('tsearch')) {
    ajax('GET', '/204');
  } else {
    ajax('search', 'GET', '/search/warmUp');
  }
}, 50000));

var searchPrefetchCache = {};  // for searching before the results page is ready
var searchFilterCache = {};    // for restoring filter if user navigates back to results
api.on.search.add(function prefetchResults(query) {
  log('[prefetchResults] prefetching for query:', query)();
  var entry = searchPrefetchCache[query];
  if (!entry) {
    entry = searchPrefetchCache[query] = {callbacks: [], response: null};
    searchOnServer({query: query, filter: (searchFilterCache[query] || {}).filter}, function(response) {
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

function scheduleAutoShow(tab) {
  log("[scheduleAutoShow] scheduling tab:", tab.id)();
  // Note: Caller should verify that tab.url is not kept and that the tab is still at tab.url.
  if (api.prefs.get("showSlider")) {
    tab.autoShowTimer = api.timers.setTimeout(function autoShow() {
      delete tab.autoShowSec;
      delete tab.autoShowTimer;
      if (api.prefs.get("showSlider")) {
        log("[autoShow]", tab.id)();
        api.tabs.emit(tab, "auto_show", null, {queue: 1});
      }
    }, tab.autoShowSec * 1000);
  }
}

function compilePatterns(arr) {
  for (var i = 0; i < arr.length; i++) {
    arr[i] = new RegExp(arr[i], "");
  }
  return arr;
}

function reTest(s) {
  return function(re) {return re.test(s)};
}
function hasId(id) {
  return function(o) {return o.id === id};
}
function idIsNot(id) {
  return function(o) {return o.id !== id};
}
function getId(o) {
  return o.id;
}

function devUriOr(uri) {
  return api.prefs.get("env") === "development" ? "http://dev.ezkeep.com:9000" : uri;
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

function getFullyQualifiedKey(key) {
  return (api.prefs.get("env") || "production") + "_" + key;
}

function getStored(key) {
  return api.storage[getFullyQualifiedKey(key)];
}

function store(key, value) {
  var qKey = getFullyQualifiedKey(key), prev = api.storage[qKey];
  if (value != null && prev !== String(value)) {
    log("[store] %s = %s (was %s)", key, value, prev)();
    api.storage[qKey] = value;
  }
}

function unstore(key) {
  delete api.storage[getFullyQualifiedKey(key)];
}

api.on.install.add(function() {
  log('[api.on.install]')();
});
api.on.update.add(function() {
  log('[api.on.update]')();
});

function getFriends() {
  ajax("GET", "/ext/user/friends", function(fr) {
    log("[getFriends]", fr)();
    friends = fr;
    friendsById = {};
    for (var i = 0; i < fr.length; i++) {
      var f = fr[i];
      friendsById[f.id] = f;
    }
  });
}

function getTags() {
  ajax("GET", "/tags", function(arr) {
    log("[getTags]", arr)();
    tags = arr;
    tagsById = tags.reduce(function(o, tag) {
      o[tag.id] = tag;
      return o;
    }, {});
  });
}

function getPrefs() {
  ajax("GET", "/ext/prefs", function(o) {
    log("[getPrefs]", o)();
    session.prefs = o[1];
    session.eip = o[2];
    socket && socket.send(["eip", session.eip]);
  });
}

function getRules() {
  ajax("GET", "/ext/pref/rules", {version: ruleSet.version}, function(o) {
    log("[getRules]", o)();
    if (o && Object.getOwnPropertyNames(o).length > 0) {
      ruleSet = o.slider_rules;
      urlPatterns = compilePatterns(o.url_patterns);
    }
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

var session, socket, onLoadingTemp;

function connectSync() {
  getRules();
  getFriends();
  getTags();
  getPrefs();
}

function authenticate(callback, retryMs) {
  if (api.prefs.get("env") === "development") {
    openLogin(callback, retryMs);
  } else {
    startSession(callback, retryMs);
  }
}

function startSession(callback, retryMs) {
  ajax("POST", "/kifi/start", {
    installation: getStored("kifi_installation_id"),
    version: api.version},
  function done(data) {
    log("[authenticate:done] reason: %s session: %o", api.loadReason, data)();
    unstore('logout');

    api.toggleLogging(data.experiments.indexOf('extension_logging') >= 0);
    data.joined = data.joined ? new Date(data.joined) : null;
    session = data;
    session.prefs = {}; // to come via socket
    socket = socket || api.socket.open(elizaBaseUri().replace(/^http/, "ws") + "/eliza/ext/ws?version=" + api.version + "&eip=" + (session.eip || ""), socketHandlers, function onConnect() {
      for (var uri in pageThreadData) {
        pageThreadData[uri].stale = true;
      }
      connectSync();
      socket.send(["get_last_notify_read_time"]);
      if (!notifications) {
        socket.send(["get_notifications", NOTIFICATION_BATCH_SIZE]);
      } else {
        socket.send(["get_missed_notifications", notifications.length ? notifications[0].time : new Date(0).toISOString()]);
        syncNumUnreadUnmutedThreads();
      }
      api.tabs.eachSelected(kifify);
    }, function onDisconnect(why) {
      reportError("socket disconnect (" + why + ")");
    });
    logEvent.catchUp();
    mixpanel.catchUp();

    ruleSet = data.rules;
    urlPatterns = compilePatterns(data.patterns);
    store("kifi_installation_id", data.installationId);
    delete session.rules;
    delete session.patterns;
    delete session.installationId;

    api.tabs.on.loading.remove(onLoadingTemp), onLoadingTemp = null;
    emitAllTabs("session_change", session);
    callback();
  },
  function fail(xhr) {
    log("[startSession:fail] xhr.status:", xhr.status)();
    if (!xhr.status || xhr.status >= 500) {  // server down or no network connection, so consider retrying
      if (retryMs) {
        api.timers.setTimeout(startSession.bind(null, callback, Math.min(60000, retryMs * 1.5)), retryMs);
      }
    } else if (getStored("kifi_installation_id")) {
      openLogin(callback, retryMs);
    } else {
      var tab = api.tabs.anyAt(webBaseUri() + "/");
      if (tab) {
        api.tabs.select(tab.id);
      } else {
        api.tabs.open(webBaseUri());
      }
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
  if (session) {
    api.tabs.each(function(tab) {
      api.icon.set(tab, 'icons/keep.faint.png');
      api.tabs.emit(tab, 'session_change', null);
    });
  }
  session = null;
  if (socket) {
    socket.close();
    socket = null;
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

api.timers.setTimeout(function() {
  for(var a={},b=0;38>b;b++) // 38 = 42 - 42/10
    a[parseInt(" 0 5 611214041 h j s x1n3c3i3j3k3l3m3n6g6r6t6u6v6w6x6y6zcyczd0d1d2d3dgdhdkdl".substr(2*b,2),36).toString(2)]=" |_i(mMe/\\n\ngor.cy!W:ahst')V,v24Juwbdl".charAt(b);
  for(var d=[],b=0;263>b;b++) // lowest prime that is an irregular prime, an Eisenstein prime, a long prime, a Chen prime, a Gaussian prime, a happy prime, a sexy prime, a safe prime, and a Higgs prime. I think.
    d.push(("000"+parseInt("1b123ebe88bc92fc7b6f4fee9c5e582f36ec9b500550157b55cdb19b55cc355db01b6dbb534d9caf9dab6aaaadb8e27c4d3673b55a93be954abaaeaab9c7d9f69a4efa5ed75736d3ba8e6d79b74979b5734f9a6e6da7d8e88fcff8bda5ff2c3e00da6a1d6fd2c2ebfbf9f63c7f8fafc230f89618c7ffbc1aeda60eaf53c7e8081fd2000".charAt(b),16).toString(2)).substr(-4));
  for(var e=d.join(""),f="",g=0;g<e.length;) {
    for(var h="";!(h in a);)
      h+=e[g],g++;
    f+=a[h]
  }
  log("\n"+f)();
});

authenticate(function() {
  if (api.loadReason === 'install') {
    log("[main] fresh install")();
    var tab = api.tabs.anyAt(webBaseUri() + "/install");
    if (tab) {
      api.tabs.navigate(tab.id, webBaseUri() + "/getting-started");
    } else {
      api.tabs.open(webBaseUri() + "/getting-started");
    }
  }
  if (api.loadReason === 'install' || api.prefs.get('env') === 'development') {
    store('prompt_to_import_bookmarks', true);
  }
}, 3000);

function reportError(errMsg, url, lineNo) {
  log('Reporting error "%s" in %s line %s', errMsg, url, lineNo)();
  if ((api.prefs.get("env") === "production") === api.isPackaged()) {
    ajax("POST", "/error/report", {message: errMsg + (url ? ' at ' + url + (lineNo ? ':' + lineNo : '') : '')});
  }
}
if (typeof window !== 'undefined') {  // TODO: add to api, find equivalent for firefox
  window.onerror = reportError;
}
