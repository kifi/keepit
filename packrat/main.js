var api = api || require("./api");

const NOTIFICATION_BATCH_SIZE = 10;
const hostRe = /^https?:\/\/([^\/]*)/;

var tabsShowingNotificationsPane = [];
var notificationsCallbacks = [];
var threadCallbacks = {};

// ===== Cached data from server

var pageData = {}; // keyed by normalized url
var messageData = {}; // keyed by thread id; todo: evict old threads from memory
var notifications;  // [] would mean user has none
var timeNotificationsLastSeen = new Date(0);
var numNotificationsNotVisited = 0;  // may include some not yet loaded
var haveAllNotifications;  // inferred
var friends = [];
var friendsById = {};
var ruleSet = {};
var urlPatterns = [];

function clearDataCache() {
  api.log("[clearDataCache]");
  pageData = {};
  messageData = {};
  notifications = null;
  timeNotificationsLastSeen = new Date(0);
  numNotificationsNotVisited = 0;
  haveAllNotifications = false;
  friends = [];
  friendsById = {};
  ruleSet = {};
  urlPatterns = [];
}

function PageData() {
  this.tabs = [];
}
PageData.prototype = {
  withThreadData: function(cb) {
    if (this.threadDataReceived) {
      cb();
    } else {
      (this.threadDataCallbacks || (this.threadDataCallbacks = [])).push(cb);
    }
  },
  dispatchThreadData: function() {
    for (var arr = this.threadDataCallbacks; arr && arr.length;) {
      arr.shift()();
    }
    delete this.threadDataCallbacks;
  }};

// ===== Server requests

function ajax(service, method, uri, data, done, fail) {  // method and uri are required
  if (service.match(/^(?:GET|POST|HEAD|OPTIONS|PUT)$/)) { // shift args if service is missing
    fail = done, done = data, data = uri, uri = method, method = service, service = "api";
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

const eventFamilies = {slider:1, search:1, extension:1, account:1, notification:1};

function logEvent(eventFamily, eventName, metaData, prevEvents) {
  if (!eventFamilies[eventFamily]) {
    api.log("#800", "[logEvent] invalid event family:", eventFamily);
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
  api.log("#aaa", "[logEvent] %s %o", ev.eventName, ev);
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

const socketHandlers = {
  denied: function() {
    api.log("[socket:denied]");
    socket.close();
    socket = null;
    session = null;
    clearDataCache();
  },
  version: function(v) {
    api.log("[socket:version]", v);
    if (api.version != v) {
      api.requestUpdateCheck();
    }
  },
  experiments: function(exp) {
    api.log("[socket:experiments]", exp);
    session.experiments = exp;
  },
  new_friends: function(fr) {
    api.log("[socket:new_friends]", fr);
    for (var i = 0; i < fr.length; i++) {
      var f = fr[i];
      if (friendsById[f.id]) {
        friends = friends.filter(function(e) {return e.id != f.id})
      }
      friends.push(f)
      friendsById[f.id] = f;
    }
  },
  url_patterns: function(patterns) {
    api.log("[socket:url_patterns]", patterns);
    urlPatterns = compilePatterns(patterns);
  },
  notifications: function(arr, numNotVisited) {  // initial load of notifications
    api.log("[socket:notifications]", arr, numNotVisited);
    if (!notifications) {
      for (var i = 0; i < arr.length; i++) {
        standardizeNotification(arr[i]);
      }
      notifications = arr;
      haveAllNotifications = arr.length < NOTIFICATION_BATCH_SIZE;
      numNotificationsNotVisited = numNotVisited;
      while (notificationsCallbacks.length) {
        notificationsCallbacks.shift()();
      }
      tellTabsNoticeCountIfChanged();
    }
  },
  notification: function(n) {  // a new notification (real-time)
    api.log("[socket:notification]", n);
    standardizeNotification(n);
    if (insertNewNotification(n)) {
      sendNotificationToTabs(n);
    }
  },
  missed_notifications: function(arr, serverTime) {
    api.log("[socket:missed_notifications]", arr, serverTime);
    for (var i = arr.length - 1; ~i; i--) {
      var n = arr[i];
      standardizeNotification(n);

      if (pageData[n.url]) {
        // One ThreadInfo in d.threads and messageData[threadId] may be outdated.
        // Really only need to load the ThreadInfo and any new messages on the thread.
        loadThreadData(n.url);
      }

      if (!insertNewNotification(n)) {
        arr.splice(i, 1);
      } else if ((new Date(serverTime) - new Date(notifications[0].time)) < 1000*60) {
        sendNotificationToTabs(n);
      }
    }
    if (arr.length) {
      tabsShowingNotificationsPane.forEach(function(tab) {
        api.tabs.emit(tab, "missed_notifications", arr);
      });
      tellTabsNoticeCountIfChanged();
    }
  },
  last_notify_read_time: function(t) {
    api.log("[socket:last_notify_read_time]", t);
    var time = new Date(t);
    if (time > timeNotificationsLastSeen) {
      timeNotificationsLastSeen = time;
    }
  },
  all_notifications_visited: function(id, time) {
    api.log("[socket:all_notifications_visited]", id, time);
    markAllNoticesVisited(id, time);
  },
  thread: function(th) {
    api.log("[socket:thread]", th);
    messageData[th.id] = th.messages;
    for (var arr = threadCallbacks[th.id]; arr && arr.length;) {
      arr.shift()({id: th.id, messages: th.messages, participants: th.messages[0].participants});
    }
    delete threadCallbacks[th.id];
  },
  message: function(threadId, message) {
    api.log("[socket:message]", threadId, message, message.nUrl);
    var d = pageData[message.nUrl];
    if (d && !(messageData[threadId] || []).some(hasId(message.id))) {
      var thread = (d.threads || []).filter(hasId(threadId))[0];
      if (thread) {
          var messages;
          if (thread.messageCount >= 1) {
            messages = messageData[threadId];
            if (messages) {
              // insert message in chronological order
              var t = new Date(message.createdAt);
              for (var i = messages.length; i > 0 && new Date(messages[i-1].createdAt) > t; i--);
              messages.splice(i, 0, message);
            }
          } else {
            messageData[threadId] = messages = [message];
          }

          var lastMessage = messages[messages.length-1];
          // until we have the updated threadinfos, do a best attempt at updating the thread
          if (!thread.createdAt) thread.createdAt = lastMessage.createdAt;
          thread.digest = lastMessage.text;
          thread.lastAuthor = lastMessage.user.id;
          thread.lastCommentedAt = lastMessage.createdAt;
          thread.messageCount = messages.length;
          messages.forEach(function(m) { thread.messageTimes[m.id] = m.createdAt; });
          thread.participants = lastMessage.participants.filter(idIsNot(session.userId));

          // keep thread in chronological order
          //var t = new Date(thread.lastCommmentedAt);
          //for (i = d.threads.length; i > 0 && new Date(d.threads[i-1].lastCommentedAt) > t; i--);
          //d.threads.splice(i, 0, thread);
      } else {
        // this is the first message of a new thread, so we should fetch its thread data
        loadThreadData(message.nUrl);
      }

      // ensure marked read if from this user
      if (message.user.id == session.userId) {
        if (new Date(message.createdAt) > new Date(d.lastMessageRead[threadId] || 0)) {
          d.lastMessageRead[threadId] = message.createdAt;
        }
      }

      d.tabs.forEach(function(tab) {
        api.tabs.emit(tab, "message", {threadId: threadId, thread: thread, message: message, read: d.lastMessageRead[threadId], userId: session.userId});
      });
      tellTabsIfCountChanged(d, "m", messageCount(d));
    }
  },
  message_read: function(nUri, threadId, time, messageId) {
    api.log("[socket:message_read]", nUri, threadId, time);

    removeNotificationPopups(threadId);

    var hasThreadId = hasId(threadId);
    for (var page in pageData) {
      var d = pageData[page];
      if (d.threads.filter(hasThreadId).length > 0) {
        d.lastMessageRead[threadId] = time;
        var thread = d.threads.filter(hasId(threadId))[0];
        d.tabs.forEach(function(tab) {
          api.tabs.emit(tab, "thread_info", {thread: thread, read: d.lastMessageRead[threadId]});
        });
        tellTabsIfCountChanged(d, "m", messageCount(d));
      }
    }
    markNoticesVisited("message", messageId, time, "/messages/" + threadId);

    tellTabsNoticeCountIfChanged();
  },
  unread_notifications_count: function(count) {
    // see comment in syncNumNotificationsNotVisited() :(
    if (numNotificationsNotVisited != count) {
      if (notifications) {
        socket.send(["get_missed_notifications", notifications.length ? notifications[0].time : new Date(0).toISOString()]);
        reportError("numNotificationsNotVisited count incorrect: " + numNotificationsNotVisited + " != " + count);
      }
      numNotificationsNotVisited = count;
      tellTabsNoticeCountIfChanged();
    }
  }
};

// ===== Handling messages from content scripts or other extension pages

api.port.on({
  deauthenticate: deauthenticate,
  get_keeps: searchOnServer,
  get_chatter: function(urls, respond) {
    api.log("[get_chatter]");
    ajax("eliza", "POST", "/eliza/ext/chatter", urls, respond);
  },
  get_keepers: function(_, respond, tab) {
    api.log("[get_keepers]", tab.id);
    var d = pageData[tab.nUri] || {};
    respond({kept: d.kept, keepers: d.keepers || [], otherKeeps: d.otherKeeps || 0});
  },
  keep: function(data, _, tab) {
    api.log("[keep]", data);
    (pageData[tab.nUri] || {}).kept = data.how;
    var bm = {
      title: data.title,
      url: data.url,
      canonical: data.canonical,
      og: data.og,
      isPrivate: data.how == "private"};
    postBookmarks(function(f) {f([bm])}, "HOVER_KEEP");
    pageData[tab.nUri].tabs.forEach(function(tab) {
      setIcon(tab, data.how);
      api.tabs.emit(tab, "kept", {kept: data.how});
    });
  },
  unkeep: function(data, _, tab) {
    api.log("[unkeep]", data);
    delete (pageData[tab.nUri] || {}).kept;
    ajax("POST", "/bookmarks/remove", data, function(o) {
      api.log("[unkeep] response:", o);
    });
    pageData[tab.nUri].tabs.forEach(function(tab) {
      setIcon(tab, false);
      api.tabs.emit(tab, "kept", {kept: null});
    });
  },
  set_private: function(data, _, tab) {
    api.log("[setPrivate]", data);
    ajax("POST", "/bookmarks/private", data, function(o) {
      api.log("[setPrivate] response:", o);
    });
    pageData[tab.nUri].tabs.forEach(function(tab) {
      api.tabs.emit(tab, "kept", {kept: data.private ? "private" : "public"});
    });
  },
  keeper_shown: function(_, _, tab) {
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
  log_event: function(data) {
    logEvent.apply(null, data);
  },
  send_message: function(data, respond, tab) {
    api.log("[send_message]", data);
    var nUri = tab.nUri || data.url;
    ajax("eliza", "POST", "/eliza/messages", data, function(o) {
      api.log("[send_message] resp:", o);
      // need ThreadInfo for this thread (new or merged), as well as the sent message.
      // might be nice to get them in the response instead of requesting over the socket.
      loadThreadData(nUri);
      respond(o);
    });
  },
  send_reply: function(data, respond) {
    api.log("[send_reply]", data);
    var id = data.threadId;
    delete data.threadId;
    ajax("eliza", "POST", "/eliza/messages/" + id, data, function(o) {
      api.log("[send_reply] resp:", o);
      respond(o);
    });
  },
  message_rendered: function(o, _, tab) {
    whenTabFocused(tab, o.threadId, function (tab) {
      var d = pageData[tab.nUri];
      var unreadNotification = false;
      for (var i = 0; i < notifications.length; i++) {
        if (notifications[i].unread && (notifications[i].thread == o.threadId || notifications[i].id == o.messageId)) {
          unreadNotification = true;
          o.messageId = notifications[i].id;
        }
      }

      if (o.forceSend || unreadNotification || (!d || !d.lastMessageRead || new Date(o.time) >= new Date(d.lastMessageRead[o.threadId] || 0))) {
        markNoticesVisited("message", o.messageId, o.time, "/messages/" + o.threadId);
        if (d && d.lastMessageRead) {
          d.lastMessageRead[o.threadId] = o.time;
          tellTabsIfCountChanged(d, "m", messageCount(d));  // tabs at this uri
        }
        tellTabsNoticeCountIfChanged();  // visible tabs
        socket.send(["set_message_read", o.messageId]);
      }
    });
  },
  set_global_read: function(o, _, tab) {
    markNoticesVisited("global", o.noticeId);
    tellTabsNoticeCountIfChanged();  // visible tabs
    socket.send(["set_global_read", o.noticeId]);
  },
  threads: function(_, respond, tab) {
    var d = pageData[tab.nUri];
    if (d) d.withThreadData(function() {
      respond({threads: d.threads, read: d.lastMessageRead});
    });
  },
  thread: function(data, respond, tab) {
    var d = pageData[tab.nUri];
    if (d) d.withThreadData(function() {
      var th = d.threads.filter(function(t) {return t.id == data.id || t.messageTimes[data.id]})[0];
      if (th && messageData[th.id]) {
        if (data.respond) {
          respond({id: th.id, messages: messageData[th.id], participants: th.participants || []});
        }
      } else {
        var id = (th || data).id;
        socket.send(["get_thread", id]);
        if (data.respond) {
          (threadCallbacks[id] || (threadCallbacks[id] = [])).push(respond);
        }
      }
    });
  },
  notifications: function(_, respond) {
    if (notifications) {
      reply();
    } else {
      notificationsCallbacks.push(reply);
    }
    function reply() {
      syncNumNotificationsNotVisited(); // sanity checking
      respond({
        notifications: notifications.slice(0, NOTIFICATION_BATCH_SIZE),
        timeLastSeen: timeNotificationsLastSeen.toISOString(),
        numNotVisited: numNotificationsNotVisited});
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
  notifications_pane: function(showing, _, tab) {
    for (var i = 0; i < tabsShowingNotificationsPane.length; i++) {
      if (tabsShowingNotificationsPane[i].id === tab.id) {
        tabsShowingNotificationsPane.splice(i--, 1);
      }
    }
    if (showing) tabsShowingNotificationsPane.push(tab);
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
  get_friends: function(_, respond) {
    respond(friends);
  },
  get_networks: function(friendId, respond) {
    socket.send(["get_networks", friendId], respond);
  },
  open_deep_link: function(link, _, tab) {
    if (tab.nUri == link.nUri) {
      awaitDeepLink(link, tab.id);
    } else {
      var d = pageData[link.nUri];
      if ((tab = d ? d.tabs[0] : api.tabs.anyAt(link.nUri))) {  // page’s normalized URI may have changed
        awaitDeepLink(link, tab.id);
        api.tabs.select(tab.id);
      } else {
        api.tabs.open(link.nUri, function(tabId) {
          awaitDeepLink(link, tabId);
        });
      }
    }
  },
  remove_notification: function(o) {
    removeNotificationPopups(o.associatedId);
  },
  await_deep_link: function(link, _, tab) {
    awaitDeepLink(link, tab.id);
  },
  report_error: function(data, _, tag) {
    // TODO: filter errors and improve fidelity/completeness of information
    //reportError(data.message, data.url, data.lineNo);
  }
});

function removeNotificationPopups(associatedId) {
  api.tabs.each(function(page) {
    api.tabs.emit(page, "remove_notification", associatedId);
  });
}

function standardizeNotification(n) {
  n.category = (n.category || "message").toLowerCase();
  for (var i = n.participants ? n.participants.length : 0; i--;) {
    if (n.participants[i].id == session.userId) {
      n.participants.splice(i, 1);
    }
  }
}

function sendNotificationToTabs(n) {
  var told = {};
  api.tabs.eachSelected(tellTab);
  tabsShowingNotificationsPane.forEach(tellTab);
  tellTabsNoticeCountIfChanged();
  api.play("media/notification.mp3");

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

  if (n.unread) {  // may have been visited before arrival
    var d = pageData[n.url];
    if (d && new Date(n.time) <= getTimeLastRead(n, d)) {
      n.unread = false;
    } else {
      numNotificationsNotVisited++;
    }
  }

  while (++i < notifications.length) {
    var n2 = notifications[i];
    if ((n.thread && n2.thread == n.thread) || (n.id == n2.id)) {
      notifications.splice(i--, 1);
      if (n2.unread) {
        decrementNumNotificationsNotVisited(n2);
      }
    }
  }

  return true;
}

function syncNumNotificationsNotVisited() {
  // We have an open issue where numNotificationsNotVisited gets off - it goes below 0
  // So either an incriment is not happening, or a decrement is happening too often.
  // The issue goes back several months (with the -1 notification issue), but has gotten
  // much worse lately. I've had dificulty consistantly reproducing, so am adding this
  // sync in until we can identify the real issue counts get off. Could be related to
  // spotty internet, or some logic error above. -Andrew
  if(socket && socket.send) {
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
        decrementNumNotificationsNotVisited(n);
      }
    }
  });
  tabsShowingNotificationsPane.forEach(function(tab) {
    api.tabs.emit(tab, "notifications_visited", {
      category: category,
      time: timeStr,
      locator: locator,
      id: id,
      numNotVisited: numNotificationsNotVisited});
  });
}

function markAllNoticesVisited(id, timeStr) {  // id and time of most recent notification to mark
  var time = new Date(timeStr);
  for (var i = 0; i < notifications.length; i++) {
    var n = notifications[i];
    if ((n.id == id || new Date(n.time) <= time) && n.unread) {
      n.unread = false;
      var d = pageData[n.url];
      if (d && new Date(n.time) > getTimeLastRead(n, d)) {
        switch (n.category) {
          case "message":
            d.lastMessageRead[n.locator.split("/")[2]] = n.time;
            tellTabsIfCountChanged(d, "m", messageCount(d));  // tabs at this uri
            break;
        }
      }
    }
  }
  numNotificationsNotVisited = notifications.filter(function(n) {
    return n.unread;
  }).length;
  tabsShowingNotificationsPane.forEach(function(tab) {
    api.tabs.emit(tab, "all_notifications_visited", {
      id: id,
      time: timeStr,
      numNotVisited: numNotificationsNotVisited});
  });

  tellTabsNoticeCountIfChanged();  // visible tabs
}

function decrementNumNotificationsNotVisited(n) {
  if (numNotificationsNotVisited <= 0) {
    api.log("#a00", "[decrementNumNotificationsNotVisited] error", numNotificationsNotVisited, n);
  }
  if (numNotificationsNotVisited > 0 || ~session.experiments.indexOf("admin")) {  // exposing -1 to admins to help debug
    numNotificationsNotVisited--;
  }
}

function getTimeLastRead(n, d) {
  return new Date(
    n.category == "message" && d.lastMessageRead ? (d.lastMessageRead[n.locator.split("/")[2]] || 0) : 0);
}

function awaitDeepLink(link, tabId, retrySec) {
  if (link.locator) {
    var tab = api.tabs.get(tabId);
    if (tab && (link.url || link.nUri).match(hostRe)[1] == tab.url.match(hostRe)[1]) {
      api.log("[awaitDeepLink]", tabId, link);
      api.tabs.emit(tab, "open_to", {trigger: "deepLink", locator: link.locator}, {queue: 1});
    } else if ((retrySec = retrySec || .5) < 5) {
      api.log("[awaitDeepLink]", tabId, "retrying in", retrySec, "sec");
      api.timers.setTimeout(awaitDeepLink.bind(null, link, tabId, retrySec + .5), retrySec * 1000);
    }
  } else {
    api.log("[awaitDeepLink] no locator", tabId, link);
  }
}

function initTab(tab, d) {  // d is pageData[tab.nUri]
  api.log("[initTab]", tab.id, "inited:", tab.inited);

  d.counts.n = numNotificationsNotVisited;
  api.tabs.emit(tab, "counts", d.counts, {queue: 1});
  if (tab.inited) return;
  tab.inited = true;

  if (ruleSet.rules.message && d.counts.m) {  // open immediately to unread message(s)
    var ids = unreadThreadIds(d.threads, d.lastMessageRead);
    api.tabs.emit(tab, "open_to", {trigger: "message", locator: "/messages" + (ids.length > 1 ? "" : "/" + ids[0])}, {queue: 1});
    ids.forEach(function(id) {
      socket.send(["get_thread", id]);
    });
  } else if (!d.kept && !d.neverOnSite && (!d.sensitive || !ruleSet.rules.sensitive)) {  // auto-engagement
    var url = tab.url;
    if (ruleSet.rules.url && urlPatterns.some(function(re) {return re.test(url)})) {
      api.log("[initTab]", tab.id, "restricted");
    } else if (ruleSet.rules.shown && d.shown) {
      api.log("[initTab]", tab.id, "shown before");
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

function dateWithoutMs(t) { // until db has ms precision
  var d = new Date(t);
  d.setMilliseconds(0);
  return d;
}

function messageCount(d) {
  var n = 0;
  for (var i = 0; i < d.threads.length; i++) {
    var th = d.threads[i], thReadTime = new Date(d.lastMessageRead[th.id] || 0);
    for (var id in th.messageTimes) {
      if (new Date(th.messageTimes[id]) > thReadTime) {
        n++; break;
      }
    }
  }
  return n;
}

function unreadThreadIds(threads, readTimes) {
  return threads.filter(function(th) {
    var readTime = new Date(readTimes[th.id] || 0);
    for (var id in th.messageTimes) {
      if (new Date(th.messageTimes[id]) > readTime) {
        return true;
      }
    }
  }).map(getId);
}

function tellTabsNoticeCountIfChanged() {
  api.tabs.eachSelected(function(tab) {
    var d = pageData[tab.nUri];
    if (d && d.counts && d.counts.n != numNotificationsNotVisited) {
      d.counts.n = numNotificationsNotVisited;
      api.tabs.emit(tab, "counts", d.counts, {queue: 1});
    }
  });
}

function tellTabsIfCountChanged(d, key, count) {
  if (d.counts[key] != count) {
    d.counts[key] = count;
    d.counts.n = numNotificationsNotVisited;
    d.tabs.forEach(function(tab) {
      api.tabs.emit(tab, "counts", d.counts, {queue: 1});
    });
  }
}

function searchOnServer(request, respond) {
  logEvent("search", "newSearch", {query: request.query, filter: request.filter});

  if (request.first && getPrefetched(request, respond)) return;

  if (!session) {
    api.log("[searchOnServer] no session");
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
      maxHits: request.lastUUID ? 5 : api.prefs.get("maxResults"),
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
  ajax("search", "GET", "/search", params,
    function(resp) {
      api.log("[searchOnServer] response:", resp);
      resp.filter = request.filter;
      resp.session = session;
      resp.admBaseUri = admBaseUri();
      resp.showScores = api.prefs.get("showScores");
      respond(resp);
    });
  return true;
}

function ymd(d) {  // yyyy-mm-dd local date
  return new Date(d - d.getTimezoneOffset() * 60000).toISOString().substr(0, 10);
}

// kifi icon in location bar
api.icon.on.click.add(function(tab) {
  api.tabs.emit(tab, "button_click", null, {queue: 1});
});

function subscribe(tab) {
  api.log("[subscribe] %i %s %s %s", tab.id, tab.url, tab.icon, tab.nUri);
  if (!tab.icon) {
    api.icon.set(tab, "icons/keep.faint.png");
  }

  if (session == null) {
    api.log("[subscribe] user not logged in")
    if (!getStored("user_logout")) { // user did not explicitly log out using our logout process
      ajax("GET", "/ext/authed", function userIsLoggedIn() {
        // user is now logged in
        authenticate(function() {
          subscribe(tab);
        });
      });
    }
    return;
  }

  var d = pageData[tab.nUri || tab.url];

  if (d) {  // no need to ask server again
    if (d.threadDataIsStale) {
      loadThreadData(d.nUri);
      d.threadDataIsStale = false;
    }
    if (tab.nUri) {  // tab is already initialized
      if (d.counts) {
        d.counts.n = numNotificationsNotVisited;
        api.tabs.emit(tab, "counts", d.counts);
      }
    } else {
      finish(tab.url);
      if (d.hasOwnProperty("kept")) {
        setIcon(tab, d.kept);
        sendInit(tab, d);
        initTab(tab, d);
      } // else wait for page data
    }
  } else {
    ajax("POST", "/ext/pageDetails", {url: tab.url}, function success(resp) {
      api.log("[subscribe] pageDetails:", resp);

      var uri = resp.normalized;

      // the initial load of the page’s thread data. probably want to prefetch threads
      // that have any unread messages, but only in that case.
      loadThreadData(uri);

      if ((api.tabs.get(tab.id) || {}).url != tab.url) return;
      d = pageData[uri] = pageData[uri] || new PageData;
      finish(uri);

      d.kept = resp.kept;
      d.position = resp.position;
      d.neverOnSite = resp.neverOnSite;
      d.sensitive = resp.sensitive;
      d.shown = resp.shown;
      d.keepers = resp.keepers || [];
      d.keeps = resp.keeps || 0;
      d.otherKeeps = d.keeps - d.keepers.length - (d.kept === "public");
      d.pageDetailsReceived = true;

      // TODO: don’t initialize this thread data here as if it were known
      d.threads = d.threads || [];
      d.counts = d.counts || {m:0, n:0};
      d.lastMessageRead = d.lastMessageRead || {};

      d.tabs.forEach(function(tab) {
        setIcon(tab, d.kept);
        sendInit(tab, d);
      });

      if (d.threadDataReceived) {
        d.tabs.forEach(function(tab) {
          initTab(tab, d);
        });
      }
    }, function fail(xhr) {
      if (xhr.status == 403) {
        session = null;
        if (socket) {
          socket.close();
          socket = null;
        }
        clearDataCache();
      }
    });
  }
  function finish(uri) {
    tab.nUri = uri;
    for (var i = 0; i < d.tabs.length; i++) {
      if (d.tabs[i].id == tab.id) {
        d.tabs.splice(i--, 1);
      }
    }
    d.tabs.push(tab);
    api.log("[subscribe:finish]", tab.id);
  }
}

function loadThreadData(url) {
  socket.send(["get_threads", url], function(threads) {
    var d = pageData[url] || pageData[threads.length ? threads[0].nUrl : ''];
    if (!d) return;

    var oldMessageCounts = !d.threads ? {} : d.threads.reduce(function(o, th) {
      o[th.id] = th.messageCount;
      return o;
    }, {});

    d.threads = threads;
    d.lastMessageRead = d.lastMessageRead || {};
    threads.forEach(function(t) {
      t.participants = t.participants.filter(idIsNot(session.userId));
      d.lastMessageRead[t.id] = t.lastMessageRead;  // TODO: make sure we do not clobber greater/later timestamp
    });
    d.counts = {n: 0, m: messageCount(d)};
    d.dispatchThreadData();

    if (!d.threadDataReceived) {
      d.threadDataReceived = true;
      if (d.pageDetailsReceived) {
        d.tabs.forEach(function(tab) {
          initTab(tab, d);
        });
      }
    }

    // Push threads with any new messages to relevant tabs and load+push their messages too.

    var threadsWithNewMessages = [];
    threads.forEach(function(th) {
      var numNew = th.messageCount - (oldMessageCounts[th.id] || 0);
      if (numNew) {
        socket.send(["get_thread", th.id]);
        (threadCallbacks[th.id] || (threadCallbacks[th.id] = [])).push(function(th) {
          d.tabs.forEach(function(tab) {
            // TODO: may want to special case (numNew == 1) for an animation
            api.tabs.emit(tab, "thread", {id: th.id, messages: th.messages, userId: session.userId});
          });
        });
        threadsWithNewMessages.push(th);
      }
    });
    if (threadsWithNewMessages.length == 1) {  // special cased to trigger an animation
      var th = threadsWithNewMessages[0];
      d.tabs.forEach(function(tab) {
        api.tabs.emit(tab, "thread_info", {thread: th, read: d.lastMessageRead[th.id]});
      });
    } else if (threadsWithNewMessages.length > 1) {
      d.tabs.forEach(function(tab) {
        api.tabs.emit(tab, "threads", {threads: d.threads, readTimes: d.lastMessageRead, userId: session.userId});
      });
    }
  });
}

function setIcon(tab, kept) {
  api.log("[setIcon] tab:", tab.id, "kept:", kept);
  api.icon.set(tab, kept ? "icons/kept.png" : "icons/keep.png");
}

function sendInit(tab, d) {
  api.tabs.emit(tab, "init", {
      kept: d.kept,
      position: d.position,
      hide: d.neverOnSite || ruleSet.rules.sensitive && d.sensitive
    }, {queue: 1});
}

function postBookmarks(supplyBookmarks, bookmarkSource) {
  api.log("[postBookmarks]");
  supplyBookmarks(function(bookmarks) {
    api.log("[postBookmarks] bookmarks:", bookmarks);
    ajax("POST", "/bookmarks/add", {
        bookmarks: bookmarks,
        source: bookmarkSource},
      function(o) {
        api.log("[postBookmarks] resp:", o);
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
  api.log("#b8a", "[tabs.on.focus] %i %o", tab.id, tab);

  for (var key in tab.focusCallbacks) {
    tab.focusCallbacks[key](tab);
  }

  delete tab.focusCallbacks;
  subscribe(tab);
  if (tab.autoShowSec != null && !tab.autoShowTimer) {
    scheduleAutoShow(tab);
  }
});

api.tabs.on.blur.add(function(tab) {
  api.log("#b8a", "[tabs.on.blur] %i %o", tab.id, tab);
  api.timers.clearTimeout(tab.autoShowTimer);
  delete tab.autoShowTimer;
});

api.tabs.on.loading.add(function(tab) {
  api.log("#b8a", "[tabs.on.loading] %i %o", tab.id, tab);
  subscribe(tab);
  logEvent("extension", "pageLoad");
});

const searchPrefetchCache = {};  // for searching before the results page is ready
const searchFilterCache = {};    // for restoring filter if user navigates back to results
api.on.search.add(function prefetchResults(query) {
  api.log('[prefetchResults] prefetching for query:', query);
  searchOnServer({query: query, filter: (searchFilterCache[query] || {}).filter}, function(response) {
    var cached = searchPrefetchCache[query];
    cached.response = response;
    while (cached.callbacks.length) cached.callbacks.shift()(response);
    api.timers.setTimeout(function () { delete searchPrefetchCache[query] }, 10000);
  });
  searchPrefetchCache[query] = { callbacks: [], response: null };
});

function getPrefetched(request, cb) {
  var cached = searchPrefetchCache[request.query];
  if (cached) {
    var logAndCb = function(r) {
      api.log('[getPrefetched] results:', r);
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

api.tabs.on.unload.add(function(tab, historyApi) {
  api.log("#b8a", "[tabs.on.unload] %i %o", tab.id, tab);
  var d = pageData[tab.nUri];
  if (d) {
    for (var i = 0; i < d.tabs.length; i++) {
      if (d.tabs[i].id == tab.id) {
        d.tabs.splice(i--, 1);
      }
    }
    if (!d.tabs.length) {
      delete pageData[tab.nUri];
    }
  }
  api.timers.clearTimeout(tab.autoShowTimer);
  delete tab.autoShowTimer;
  delete tab.nUri;
  delete tab.inited;
  delete tab.focusCallbacks;
  if (historyApi) {
    api.tabs.emit(tab, "reset");
  }
});

function scheduleAutoShow(tab) {
  api.log("[scheduleAutoShow] scheduling tab:", tab.id);
  // Note: Caller should verify that tab.url is not kept and that the tab is still at tab.url.
  if (api.prefs.get("showSlider")) {
    tab.autoShowTimer = api.timers.setTimeout(function autoShow() {
      delete tab.autoShowSec;
      delete tab.autoShowTimer;
      if (api.prefs.get("showSlider")) {
        api.log("[autoShow]", tab.id);
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
    api.log("[store] %s = %s (was %s)", key, value, prev);
    api.storage[qKey] = value;
  }
}

api.on.install.add(function() {
  logEvent("extension", "install");
});
api.on.update.add(function() {
  logEvent("extension", "update");
});

function getFriends() {
  ajax("GET", "/ext/user/friends", function(fr) {
    api.log("[getFriends]", fr);
    friends = fr;
    friendsById = {};
    for (var i = 0; i < fr.length; i++) {
      var f = fr[i];
      friendsById[f.id] = f;
    }
  });
}

function getPrefs() {
  ajax("GET", "/ext/prefs", function(o) {
    api.log("[getPrefs]", o);
    session.prefs = o[1];
  });
}

function getRules() {
  ajax("GET", "/ext/pref/rules", {version: ruleSet.version}, function(o) {
    api.log("[getRules]", o);
    if (o && Object.getOwnPropertyNames(o).length > 0) {
      ruleSet = o.slider_rules;
      urlPatterns = compilePatterns(o.url_patterns);
    }
  });
}


// ===== Session management

var session, socket, onLoadingTemp;

function connectSync() {
  getRules();
  getFriends();
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
    api.log("[authenticate:done] reason: %s session: %o", api.loadReason, data);
    logEvent("extension", "authenticated");

    session = data;
    session.prefs = {}; // to come via socket
    socket = api.socket.open(elizaBaseUri().replace(/^http/, "ws") + "/eliza/ext/ws", socketHandlers, function onConnect() {
      for (var nUri in pageData) {
        pageData[nUri].threadDataIsStale = true;
      }
      socket.send(["get_last_notify_read_time"]);
      connectSync();
      if (!notifications) {
        socket.send(["get_notifications", NOTIFICATION_BATCH_SIZE]);
      } else {
        socket.send(["get_missed_notifications", notifications.length ? notifications[0].time : new Date(0).toISOString()]);
      }
      syncNumNotificationsNotVisited();
      api.tabs.eachSelected(subscribe);
    }, function onDisconnect(why) {
      reportError("socket disconnect (" + why + ")");
    });
    logEvent.catchUp();

    ruleSet = data.rules;
    urlPatterns = compilePatterns(data.patterns);
    store("kifi_installation_id", data.installationId);
    delete session.rules;
    delete session.patterns;
    delete session.installationId;

    api.tabs.on.loading.remove(onLoadingTemp), onLoadingTemp = null;
    api.tabs.each(function(page) {
      api.tabs.emit(page, "session_change", session);
    });
    callback();
  },
  function fail(xhr) {
    api.log("[startSession:fail] xhr.status:", xhr.status);
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
  api.log("[openLogin]");
  var baseUri = webBaseUri();
  api.popup.open({
    name: "kifi-authenticate",
    url: baseUri + "/login",
    width: 1020,
    height: 530}, {
    navigate: function(url) {
      if (url == baseUri + "/#_=_" || url == baseUri + "/") {
        api.log("[openLogin] closing popup");
        this.close();
        startSession(callback, retryMs);
      }
    }});
}

function deauthenticate() {
  api.log("[deauthenticate]");
  session = null;
  if (socket) {
    socket.close();
    socket = null;
  }
  clearDataCache();
  api.popup.open({
    name: "kifi-deauthenticate",
    url: webBaseUri() + "/logout#_=_",
    width: 200,
    height: 100}, {
    navigate: function(url) {
      if (url == webBaseUri() + "/#_=_") {
        api.log("[deauthenticate] closing popup");
        this.close();
      }
      api.tabs.each(function(tab) {
        api.icon.set(tab, "icons/keep.faint.png");
        api.tabs.emit(tab, "session_change", null);
      });
    }
  })
}

// ===== Main, executed upon install (or reinstall), update, re-enable, and browser start

logEvent("extension", "started");

authenticate(function() {
  if (api.loadReason == "install") {
    api.log("[main] fresh install");
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
  api.log('Reporting error "%s" in %s line %s', errMsg, url, lineNo);
  if ((api.prefs.get("env") === "production") === api.isPackaged()) {
    ajax("POST", "/error/report", {message: errMsg + (url ? ' at ' + url + (lineNo ? ':' + lineNo : '') : '')});
  }
}
if (typeof window !== 'undefined') {  // TODO: add to api, find equivalent for firefox
  window.onerror = reportError;
}
