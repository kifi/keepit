/*jshint globalstrict:true */
'use strict';

var api = api || require('./api');
var log = log || api.log;
var ThreadList = ThreadList || require('./threadlist').ThreadList;

var THREAD_BATCH_SIZE = 10;

//                          | sub |    |------- country domain -------|-- generic domain --|           |-- port? --|
var domainRe = /^https?:\/\/[^\/]*?((?:[^.\/]+\.[^.\/]{2,3}\.[^.\/]{2}|[^.\/]+\.[^.\/]{2,6}|localhost))(?::\d{2,5})?(?:$|\/)/;
var hostRe = /^https?:\/\/([^\/]+)/;

var tabsByUrl = {}; // normUrl => [tab]
var tabsByLocator = {}; // locator => [tab]
var tabsTagging = []; // [tab]
var threadListCallbacks = {}; // normUrl => [function]
var threadCallbacks = {}; // threadID => [function]
var threadReadAt = {}; // threadID => Date (only if read recently in this browser)

// ===== Cached data from server

var pageData = {}; // normUrl => PageData
var threadLists = {}; // normUrl => ThreadList (special keys: 'all', 'sent', 'unread')
var threadsById = {}; // threadId => thread (notification JSON)
var messageData = {}; // threadId => [message, ...]; TODO: evict old threads from memory
var friends = [];
var friendsById = {};
var ruleSet = {};
var urlPatterns = [];
var tags;  // [] means user has none
var tagsById;

function clearDataCache() {
  log('[clearDataCache]')();
  tabsByUrl = {};
  tabsByLocator = {};
  tabsTagging = [];
  threadListCallbacks = {};
  threadCallbacks = {};
  threadReadAt = {};

  pageData = {};
  threadLists = {};
  threadsById = {};
  messageData = {};
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
    var thread = threadsById[threadId];
    if (thread) {
      thread.participants = participants;
    }
    forEachTabAtLocator('/messages/' + threadId, function(tab) {
      api.tabs.emit(tab, 'participants', participants);  // TODO: send threadId too
    });
  },
  thread_muted: function(threadId, muted) {
    log("[socket:thread_muted]", threadId, muted)();
    var thread = threadsById[threadId];
    if (thread) {
      thread.muted = muted;
    }
    forEachTabAtLocator('/messages/' + threadId, function(tab) {
      api.tabs.emit(tab, 'muted', muted);  // TODO: send threadId too
    });
  },
  url_patterns: function(patterns) {
    log("[socket:url_patterns]", patterns)();
    urlPatterns = compilePatterns(patterns);
  },
  notification: function(n) {  // a new notification (real-time)
    log("[socket:notification]", n)();
    standardizeNotification(n);
    if (insertNewNotification(n)) {
      handleRealTimeNotification(n);
      tellVisibleTabsNoticeCountIfChanged();
    }
  },
  all_notifications_visited: function(id, time) {
    log('[socket:all_notifications_visited]', id, time)();
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
    forEachTabAtLocator('/messages/' + threadId, function(tab) {
      api.tabs.emit(tab, "message", {threadId: threadId, message: message, userId: session.user.id});
    });
    var messages = messageData[threadId];
    if (messages) {
      insertUpdateChronologically(messages, message, 'createdAt');
    }
  },
  message_read: function(nUri, threadId, time, messageId) {
    log("[socket:message_read]", nUri, threadId, time)();
    removeNotificationPopups(threadId);
    markRead('message', threadId, messageId, time);
  },
  unread_notifications_count: function(count) {
    // see comment in syncNumUnreadUnmutedThreads() :(
    if (threadLists.all && threadLists.all.numUnreadUnmuted !== count) {
      reportError('numUnreadUnmuted incorrect: ' + threadLists.all.numUnreadUnmuted + ' != ' + count);
      threadLists.all.numUnreadUnmuted = count;
      tellVisibleTabsNoticeCountIfChanged();
      requestMissedNotifications();
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
    ajax('search', 'POST', '/search/events/' + data[0], data[1]);
  },
  send_message: function(data, respond, tab) {
    data.extVersion = api.version;
    ajax('eliza', 'POST', '/eliza/messages', data, function(o) {
      log("[send_message] resp:", o)();
      // thread (notification) JSON comes via socket
      messageData[o.parentId] = o.messages;
      respond({threadId: o.parentId});
    });
  },
  send_reply: function(data, respond) {
    var threadId = data.threadId;
    delete data.threadId;
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
      if (markRead('message', o.threadId, o.messageId, o.time) || o.forceSend) {
        socket.send(['set_message_read', o.messageId]);
      }
    });
  },
  participants: function(id, respond, tab) {
    var th = threadsById[id];
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
          socket.send(['get_thread', id]);
          threadCallbacks[id] = [reply];
        }
      }
    }
    function reply(o) {
      respond(o.messages[o.messages.length - 1].participants);
    }
  },
  set_global_read: function(o, _, tab) {
    markRead('global', o.threadId, o.messageId, o.time);
    socket.send(['set_global_read', o.messageId]);
  },
  thread: function(id, respond, tab) {
    var msgs = messageData[id];
    if (msgs) {
      respond({id: id, messages: msgs});
    } else {
      var tc = threadCallbacks[id];
      if (tc) {
        tc.push(respond);
      } else {
        socket.send(['get_thread', id]);
        threadCallbacks[id] = [respond];
      }
    }
  },
  get_page_thread_count: function(_, respond, tab) {
    var list = threadLists[tab.nUri];
    if (list) {
      reply(list);
    } else {
      (threadListCallbacks[tab.nUri] || (threadListCallbacks[tab.nUri] = [])).push(reply);
    }
    function reply(tl) {
      respond({count: tl.ids.length, id: tl.ids.length === 1 ? tl.ids[0] : undefined});
    }
  },
  get_threads: function(kind, respond, tab) {
    var listKey = kind === 'page' ? tab.nUri : kind;
    var list = threadLists[listKey];
    if (list) {
      reply(list);
    } else {
      (threadListCallbacks[listKey] || (threadListCallbacks[listKey] = [])).push(reply);
    }
    if (kind === 'all') {
      syncNumUnreadUnmutedThreads(); // sanity checking
    }
    function reply(tl) {
      respond({
        threads: tl.ids.slice(0, THREAD_BATCH_SIZE).map(idToThread),
        anyUnread: tl.anyUnread()});
      if (kind === 'page') {  // prefetch
        tl.ids.forEach(function (id) {
          if (!messageData[id] && !threadCallbacks[id]) {
            socket.send(['get_thread', id]);
            threadCallbacks[id] = [];
          }
        });
      }
    }
  },
  get_older_threads: function(o, respond, tab) {
    var time = new Date(o.time);
    var list = threadLists[o.kind === 'page' ? tab.nUri : o.kind];
    var n = list.ids.length;
    for (var i = n - 1; ~i && new Date(threadsById[list.ids[i]].time) < time; i--);
    if (i < n - 1) {
      respond(list.ids.slice(i, i + THREAD_BATCH_SIZE).map(idToThread));
    } else if (list.includesOldest) {
      respond([]);
    } else {
      var oldest = list.ids[n - 1];
      var socketMessage = {
        all: ['get_threads_before'],
        unread: ['get_unread_threads_before'],
        sent: ['get_sent_threads_before'],
        page: ['get_page_threads_before', tab.nUri]
      }[o.kind];
      socketMessage.push(THREAD_BATCH_SIZE, o.time);
      socket.send(socketMessage, function(arr) {
        arr.forEach(function (th) {
          standardizeNotification(th);
          threadsById[th.thread] = th;
        });
        if (list.ids[list.ids.length - 1] === oldest) {
          list.insertOlder(arr.map(getThreadId));
          if (arr.length < THREAD_BATCH_SIZE) {
            list.includesOldest = true;
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
  all_notifications_visited: function(o) {
    markAllNoticesVisited(o.id, o.time);
    socket.send(['set_all_notifications_visited', o.id]);
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
  remove_notification: function(threadId) {
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
  add_participants: function(data, respond, tab) {
    var threadId = data.threadId,
      userIds = data.userIds;
    socket.send(['add_participants_to_thread', threadId, userIds]);
  },
  is_muted: function(threadId, respond, tab) {
    var th = threadsById[threadId];
    respond({
      success: Boolean(th),
      response: Boolean(th && th.muted)
    });
  },
  mute_thread: function(threadId, respond, tab) {
    socket.send(['mute_thread', threadId]);
    threadsById[threadId].muted = true;
  },
  unmute_thread: function(threadId, respond, tab) {
    socket.send(['unmute_thread', threadId]);
    threadsById[threadId].muted = false;
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

function gotLatestThreads(kind, arr, numUnreadUnmuted) {  // initial load
  log('[gotLatestThreads]', kind, arr, numUnreadUnmuted)();
  arr.forEach(function (n) {
    standardizeNotification(n);
    threadsById[n.thread] = n;
  });
  var list = threadLists[kind] = new ThreadList(threadsById, arr.map(getThreadId), numUnreadUnmuted);
  list.includesOldest = arr.length < THREAD_BATCH_SIZE;
  threadListCallbacks[kind].forEach(function (callback) {
    callback(list);
  });
  delete threadListCallbacks[kind];
  if (kind === 'all') {
    tellVisibleTabsNoticeCountIfChanged();
  }
}

function removeNotificationPopups(threadId) {
  emitAllTabs('remove_notification', threadId);
}

function standardizeNotification(n) {
  n.category = (n.category || "message").toLowerCase();
  n.unread = n.unread || (n.unreadAuthors > 0);
}

function handleRealTimeNotification(n) {
  api.tabs.eachSelected(function (tab) {
    api.tabs.emit(tab, 'show_notification', n, {queue: true});
  });
  if (n.unread && !n.muted) {
    api.play('media/notification.mp3');
  }
}

function insertNewNotification(n) {
  var n0 = threadsById[n.thread];
  // proceed only if we don’t already have this notification or a newer one for the same thread
  if (!n0 || n0.id !== n.id && new Date(n0.time) < new Date(n.time)) {
    threadsById[n.thread] = n;
    // need to mark read if new message was already viewed
    if (n.unread && threadReadAt[n.thread] >= new Date(n.time)) {
      n.unread = false;
      n.unreadAuthors = n.unreadMessages = 0;
    }
    var keys = {all: null, page: n.url};
    if (n.unread) {
      keys.unread = null;
    }
    if (isSent(n)) {
      keys.sent = null;
    }
    for (var kind in keys) {
      var list = threadLists[keys[kind] || kind];
      if (list) {
        list.insert(n);
        var locator = kind !== 'page' ? '/messages:' + kind : '/messages';
        forEachTabAtLocator(locator, function (tab) {
          api.tabs.emit(tab, 'new_thread', {kind: kind, thread: n}, {queue: true});
        });
      }
    }
    return true;
  }
}

function syncNumUnreadUnmutedThreads() {
  // We have an open issue where numUnreadUnmuted gets off - it goes below 0
  // So either an increment is not happening, or a decrement is happening too often.
  // The issue goes back several months (with the -1 notification issue), but has gotten
  // much worse lately. I've had difficulty consistantly reproducing, so am adding this
  // sync in until we can identify the real issue counts get off. Could be related to
  // spotty internet, or some logic error above. -Andrew
  if (socket) {
    socket.send(['get_unread_notifications_count']);
  }
}

function requestMissedNotifications() {
  var timeStr = threadLists.all.ids.length ? threadsById[threadLists.all.ids[0]].time : new Date(0).toISOString();
  socket.send(['get_threads_since', timeStr], function gotThreadsSince(arr, serverTimeStr) {
    log('[gotThreadsSince]', arr, serverTimeStr)();
    var serverTime = new Date(serverTimeStr);
    for (var i = arr.length; i--;) {
      var n = arr[i];
      standardizeNotification(n);
      if (insertNewNotification(n)) {
        if (serverTime - new Date(n.time) < 60000) {
          handleRealTimeNotification(n);
        }
        var md = messageData[n.thread];
        if (md && !threadCallbacks[n.thread]) {
          socket.send(['get_thread', n.thread]);  // TODO: "get_thread_since" (don't need messages already loaded)
          threadCallbacks[n.thread] = [];  // TODO: add callback that updates tabs at /messages/threadId
        }
      }
    }
    tellVisibleTabsNoticeCountIfChanged();
  });
}

// messageId is of last read message, timeStr is its createdAt time.
function markRead(category, threadId, messageId, timeStr) {
  var time = new Date(timeStr);
  if (!(threadReadAt[threadId] > time)) {
    threadReadAt[threadId] = time;
  }
  var th = threadsById[threadId];
  if (th && th.unread && (th.id === messageId || new Date(th.time) <= time)) {
    th.unread = false;
    th.unreadAuthors = th.unreadMessages = 0;
    threadLists.unread.remove(th.thread);
    if (!th.muted) {
      var tlKeys = ['all', 'unread', th.url];
      if (isSent(th)) {
        tlKeys.push('sent');
      }
      tlKeys.forEach(function (key) {
        var tl = threadLists[key];
        if (tl) {
          tl.decNumUnreadUnmuted();
        }
      });
    }

    forEachTabAtThreadList(function(tab, tl) {
      api.tabs.emit(tab, 'thread_read', {
        category: category,
        time: timeStr,
        threadId: threadId,
        id: messageId,
        anyUnread: tl.anyUnread()});
    });

    tellVisibleTabsNoticeCountIfChanged();
    return true;
  }
}

function markAllNoticesVisited(id, timeStr) {  // id and time of most recent notification to mark
  var time = new Date(timeStr);
  threadLists.all.forEachUnread(function (threadId, th) {
    if (th.id === id || new Date(th.time) <= time) {
      th.unread = false;
      th.unreadAuthors = th.unreadMessages = 0;
      if (!th.muted) {
        threadLists.all.decNumUnreadUnmuted();
      }
    }
  });
  // TODO: update numUnreadUnmuted for each ThreadList in threadLists?

  forEachTabAtThreadList(function(tab, tl) {
    api.tabs.emit(tab, 'all_threads_read', {
      id: id,
      time: timeStr,
      anyUnread: tl.anyUnread()});
  });

  tellVisibleTabsNoticeCountIfChanged();
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

var threadListLocatorRe = /^\/messages(:[a-z]+)?$/;
function forEachTabAtThreadList(f) {
  for (var loc in tabsByLocator) {
    var m = loc.match(threadListLocatorRe);
    if (m) {
      var kind = (m[1] || ':page').substr(1);
      tabsByLocator[loc].forEach(function (tab) {
        f(tab, threadLists[kind === 'page' ? tab.nUri : kind], kind);
      });
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
  api.tabs.eachSelected(function(tab) {
    if (tab.count !== threadLists.all.numUnreadUnmuted) {
      tab.count = threadLists.all.numUnreadUnmuted;
      api.tabs.emit(tab, 'count', tab.count, {queue: 1});
    }
  });
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
      stashTab(tab, uri);
      kififyWithPageData(tab, d);
    }
  } else {
    ajax('POST', '/ext/pageDetails', {url: url}, gotPageDetailsFor.bind(null, url, tab), function fail(xhr) {
      if (xhr.status === 403) {
        clearSession();
      }
    });
  }

  // page threads
  var pt = threadLists[uri];
  if (!pt || pt.stale) {  // TODO: replacement for .stale based on socket.ver
    var callbacks = threadListCallbacks[uri];
    if (!callbacks) {
      callbacks = threadListCallbacks[uri] = [];
      socket.send(['get_page_threads', url, THREAD_BATCH_SIZE], gotPageThreads.bind(null, uri));
    }
    callbacks.push(gotPageThreadsFor.bind(null, url, tab));
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
        if (api.prefs.get("showSlider")) {
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
  d.otherKeeps = d.keeps - d.keepers.length - (d.kept === "public");

  if (!tabIsOld) {
    pageData[nUri] = d;
    stashTab(tab, nUri);
    kififyWithPageData(tab, d);
  }
}

function gotPageThreads(uri, nUri, threads, numUnreadUnmuted) {
  log('[gotPageThreads] n:', threads.length, uri, nUri !== uri ? nUri : '')();

  // incorporating new threads into our cache and noting any changes
  var numNewThreads = 0;
  var updatedThreads = [];
  threads.forEach(function (th) {
    var oldTh = threadsById[th.thread];
    if (!oldTh || oldTh.id !== th.id && new Date(oldTh.time) <= new Date(th.time)) {
      threadsById[th.thread] = th;
      if (th.unread && threadReadAt[th.thread] >= new Date(th.time)) {
        th.unread = false;
        th.unreadAuthors = th.unreadMessages = 0;
        if (!th.muted) {
          numUnreadUnmuted--;
        }
      }
      if (oldTh) {
        updatedThreads.push(oldTh);
      } else {
        numNewThreads++;
      }
    }
  });

  // reusing (sharing) the page ThreadList of an earlier normalization of the URL if possible
  var pt = threadLists[nUri] || threadLists[threads.length ? threads[0].url : ''];
  if (pt) {
    pt.insertAll(threads);
  } else {
    pt = new ThreadList(threadsById, threads.map(getThreadId), numUnreadUnmuted);
  }

  // invoking callbacks
  var callbacks = threadListCallbacks[uri];
  while (callbacks && callbacks.length) {
    callbacks.shift()(pt, nUri);
  }
  delete threadListCallbacks[uri];

  // sending new page threads (or just the count) to tabs at '/messages' on this page
  if (numNewThreads) {
    forEachTabAtUriAndLocator(uri, nUri, '/messages', function(tab) {
      api.tabs.emit(tab, 'page_threads', pt.ids.map(idToThread));  // TODO: write handler in notices.js
    });
    // forEachTabAtUriAndLocator(uri, nUri, /^\/messages:.*/, function(tab) {
    //   api.tabs.emit(tab, 'page_thread_count', pt.ids.length);  // TODO: write handler in notices.js
    // });
  }

  // updating tabs currently displaying any updated threads
  updatedThreads.forEach(function (th) {
    if (threadCallbacks[th.thread]) {
      threadCallbacks[th.thread].push(updateThreadInTabs.bind(null, th));
    } else {
      socket.send(['get_thread', th.thread]);
      threadCallbacks[th.thread] = [updateThreadInTabs.bind(null, th)];
    }
  });
  function updateThreadInTabs(oldTh, o) {
    forEachTabAtLocator('/messages/' + o.id, function(tab) {
      if (o.messages.length === oldTh.messages + 1) {
        api.tabs.emit(tab, 'message', {
          threadId: o.id,
          message: o.messages[o.messages.length - 1]});
      } else {
        api.tabs.emit(tab, 'thread', {id: o.id, messages: o.messages});
      }
    });
  }
}

function gotPageThreadsFor(url, tab, pt, nUri) {
  var tabIsOld = api.tabs.get(tab.id) !== tab || url.split('#', 1)[0] !== tab.url.split('#', 1)[0];
  log('[gotPageThreadsFor]', tab.id, tabIsOld ? 'OLD' : '', url, nUri === url ? '' : nUri, pt.ids)();

  if (!tabIsOld) {
    threadLists[nUri] = pt;
    if (ruleSet.rules.message && pt.numUnreadUnmuted) {  // open immediately to unread message(s)
      api.tabs.emit(tab, 'open_to', {
        locator: pt.numUnreadUnmuted === 1 ? '/messages/' + pt.firstUnreadUnmuted() : '/messages',
        trigger: 'message'
      }, {queue: 1});
      pt.forEachUnread(function(threadId) {
        if (!threadCallbacks[threadId]) {
          socket.send(['get_thread', threadId]);  // prefetch
          threadCallbacks[threadId] = [];
        }
      });
    }
  }
}

function isSent(th) {
  return th.firstAuthor && th.participants[th.firstAuthor].id === session.user.id;
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
    delete threadLists[tab.nUri];
    delete threadListCallbacks[tab.nUri];
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
  delete tab.count;
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
function getThreadId(n) {
  return n.thread;
}
function idToThread(id) {
  return threadsById[id];
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
      // TODO: better stale thread data management
      // for (var key in threadLists) {
      //   threadLists[key].stale = true;
      // }
      connectSync();
      if (!threadLists.all) {
        var socketRequests = {
          all: 'get_latest_threads',
          unread: 'get_unread_threads',
          sent: 'get_sent_threads'
        };
        for (var kind in socketRequests) {
          socket.send([socketRequests[kind], THREAD_BATCH_SIZE], gotLatestThreads.bind(null, kind));
          threadListCallbacks[kind] = [];
        }
      } else {
        requestMissedNotifications();
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
        if (tab.url.replace(/\/#.*$/, "") === webBaseUri()) {
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
  if (api.loadReason == "install") {
    log("[main] fresh install")();
    var tab = api.tabs.anyAt(webBaseUri() + "/install");
    if (tab) {
      api.tabs.navigate(tab.id, webBaseUri() + "/getting-started");
    } else {
      api.tabs.open(webBaseUri() + "/getting-started");
    }
  }
  if (api.loadReason == "install" || api.prefs.get("env") === "development") {
    postBookmarks(api.bookmarks.getAll, "INIT_LOAD");
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
