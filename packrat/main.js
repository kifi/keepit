var api = api || require("./api");

const NOTIFICATION_BATCH_SIZE = 10;  // also in notices.js

var tabsShowingNotificationsPane = [];
var notificationsCallbacks = [];

// ===== Cached data from server

var pageData = {};
var notifications;  // [] would mean user has none
var newNotificationIdxs = [];  // derived
var timeNotificationsLastSeen = new Date(0);
var numNotificationsNotVisited = 0;
var haveAllNotifications;  // inferred
var friends = [];
var friendsById = {};
var ruleSet = {};
var urlPatterns = [];

function clearDataCache() {
  pageData = {};
  notifications = null;
  newNotificationIdxs = [];
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
  this.on2Callbacks = [];
}
PageData.prototype = {
  on2: function(cb) {
    if (this.counts) {
      cb();
    } else {
      this.on2Callbacks.push(cb);
    }
  },
  dispatchOn2: function() {
    while (this.on2Callbacks.length) {
      this.on2Callbacks.shift()();
    }
  }};

// ===== Server requests

function ajax(method, uri, data, done, fail) {  // method and uri are required
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

  api.request(method, apiBaseUri() + uri, data, done, fail);
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
    ev.time = +new Date;
    logEvent.queue.push(ev);
    if (logEvent.queue.length > 50) {
      logEvent.queue.shift();  // discard oldest
    }
  }
}
logEvent.queue = [];
logEvent.catchUp = function() {
  var t = +new Date;
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
  experiments: function(exp) {
    api.log("[socket:experiments]", exp);
    session.experiments = exp;
  },
  friends: function(fr) {
    api.log("[socket:friends]", fr);
    friends = fr;
    friendsById = {};
    for (var i = 0; i < fr.length; i++) {
      var f = fr[i];
      friendsById[f.id] = f;
    }
  },
  new_friends: function(fr) {
    api.log("[socket:new_friends]", fr);
    for (var i = 0; i < fr.length; i++) {
      var f = fr[i];
      friends.push(f)
      friendsById[f.id] = f;
    }
  },
  slider_rules: function(o) {
    api.log("[socket:slider_rules]", o);
    ruleSet = o;
  },
  url_patterns: function(patterns) {
    api.log("[socket:url_patterns]", patterns);
    urlPatterns = compilePatterns(patterns);
  },
  uri_1: function(uri, o) {
    api.log("[socket:uri_1]", o);
    var d = pageData[uri];
    if (d) {
      d.kept = o.kept;
      d.neverOnSite = o.neverOnSite;
      d.sensitive = o.sensitive;
      d.tabs.forEach(function(tab) {
        setIcon(tab, d.kept);
        sendKept(tab, d);
      });
    }
  },
  uri_2: function(uri, o) {
    api.log("[socket:uri_2]", o);
    var d = pageData[uri], dPrev, i;
    if (d) {
      dPrev = clone(d);
      d.shown = o.shown;
      d.keepers = o.keepers || [];
      d.keeps = o.keeps || 0;
      d.otherKeeps = d.keeps - d.keepers.length - (d.kept == "public" ? 1 : 0);
      d.following = o.following;
      d.comments = o.comments || [];
      d.threads = o.threads || [];
      d.messages = {};
      d.lastCommentRead = o.lastCommentRead;
      d.lastMessageRead = o.lastMessageRead || {};
      d.counts = {
        n: numNotificationsNotVisited,
        c: commentCount(d),
        m: messageCount(d)};
      d.tabs.forEach(function(tab) {
        initTab(tab, d);
      });
      d.dispatchOn2();

      // send tabs any missed updates
      if (dPrev.comments) {
        for (i = dPrev.comments.length; i < d.comments.length; i++) {  // TODO: combine if more than one
          d.tabs.forEach(function(tab) {
            api.tabs.emit(tab, "comment", {comment: d.comments[i], userId: session.userId});
          });
        }
      }
      if (dPrev.threads) {
        var threadsWithNewMessages = [];
        d.threads.forEach(function(th) {
          var thPrev = dPrev.threads.filter(hasId(th.id))[0];
          var numNew = th.messageCount - (thPrev && thPrev.messageCount || 0);
          if (numNew) {
            socket.send(["get_thread", th.id]);
            (d.threadCallbacks = d.threadCallbacks || []).push({id: th.id, respond: function(th) {
              d.tabs.forEach(function(tab) {
                // TODO: may want to special case (numNew == 1) for an animation
                api.tabs.emit(tab, "thread", {id: th.id, messages: th.messages, userId: session.userId});
              });
            }});
            threadsWithNewMessages.push(th);
          }
        });
        if (threadsWithNewMessages.length == 1) {
          var th = threadsWithNewMessages[0];
          d.tabs.forEach(function(tab) {
            api.tabs.emit(tab, "thread_info", {thread: th, read: d.lastMessageRead[th.id]});
          });
        } else if (threadsWithNewMessages.length > 1) {
          d.tabs.forEach(function(tab) {
            api.tabs.emit(tab, "threads", {threads: d.threads, readTimes: d.lastMessageRead, userId: session.userId});
          });
        }
      }
    }
  },
  notifications: function(arr, numNotVisited) {  // initial load of notifications
    api.log("[socket:notifications]", arr, numNotVisited);
    if (!notifications) {
      notifications = arr;
      haveAllNotifications = arr.length < NOTIFICATION_BATCH_SIZE;
      numNotificationsNotVisited = numNotVisited;
      identifyNewNotices();
      while (notificationsCallbacks.length) {
        notificationsCallbacks.shift()();
      }
      tellTabsNoticeCountIfChanged();
    }
  },
  notification: function(n) {  // a new notification (real-time)
    api.log("[socket:notification]", n);
    if (insertNewNotification(n)) {
      identifyNewNotices();
      var told = {};
      api.tabs.eachSelected(tellTab);
      tabsShowingNotificationsPane.forEach(tellTab);
      tellTabsNoticeCountIfChanged();
    }
    function tellTab(tab) {
      if (told[tab.id]) return;
      told[tab.id] = true;
      api.tabs.emit(tab, "new_notification", n);
    }
  },
  missed_notifications: function(arr) {
    api.log("[socket:missed_notifications]", arr);
    for (var i = arr.length - 1; ~i; i--) {
      if (!insertNewNotification(arr[i])) {
        arr.splice(i, 1);
      }
    }
    if (arr.length) {
      identifyNewNotices();
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
      identifyNewNotices();
      tellTabsNoticeCountIfChanged();
    }
  },
  comment: function(nUri, c) {
    api.log("[socket:comment]", c);
    var d = pageData[nUri];
    if (d && d.comments) {
      d.comments.push(c);
      d.tabs.forEach(function(tab) {
        api.tabs.emit(tab, "comment", {comment: c, userId: session.userId});
      });
      tellTabsIfCountChanged(d, "c", commentCount(d));
    }
  },
  comment_read: function(nUri, time, id) {
    api.log("[socket:comment_read]", nUri, time);
    var d = pageData[nUri];
    if (!d || new Date(d.lastCommentRead || 0) < new Date(time)) {
      markNoticesVisited("comment", nUri, id, time);
      if (d) {
        d.lastCommentRead = time;
        tellTabsIfCountChanged(d, "c", commentCount(d));
      }
      tellTabsNoticeCountIfChanged();
    }
  },
  thread: function(th) {
    api.log("[socket:thread]", th);
    var d = pageData[th.uri];
    if (d && d.messages) {
      d.messages[th.id] = th.messages;
      if (d.threadCallbacks) {
        for (var i = 0; i < d.threadCallbacks.length; i++) {
          var cb = d.threadCallbacks[i];
          if (th.id == cb.id || th.messages.some(hasId(cb.id))) {
            cb.respond({id: th.id, messages: th.messages});
            d.threadCallbacks.splice(i--, 1);
          }
        }
      }
    }
  },
  message: function(nUri, th, message) {
    api.log("[socket:message]", nUri, th, message);
    var d = pageData[nUri];
    if (d && d.threads) {
      // remove old copy of thread
      for (var i = 0, n = d.threads.length; i < n; i++) {
        if (d.threads[i].id == th.id) {
          d.threads.splice(i, 1);
          break;
        }
      }
      // insert thread in chronological order
      var t = new Date(th.lastCommentedAt);
      for (i = d.threads.length; i > 0 && new Date(d.threads[i-1].lastCommentedAt) > t; i--);
      d.threads.splice(i, 0, th);
      // insert message in chronological order
      if (th.messageCount > 1) {
        var messages = d.messages[th.id];
        if (messages) {
          t = new Date(message.createdAt);
          for (i = messages.length; i > 0 && new Date(messages[i-1].createdAt) > t; i--);
          messages.splice(i, 0, message);
        }
      } else {
        d.messages[th.id] = [message];
      }
      // ensure marked read if from this user
      if (message.user.id == session.userId) {
        if (new Date(message.createdAt) > new Date(d.lastMessageRead[th.id] || 0)) {
          d.lastMessageRead[th.id] = message.createdAt;
        }
      }
      d.tabs.forEach(function(tab) {
        api.tabs.emit(tab, "message", {thread: th, message: message, read: d.lastMessageRead[th.id], userId: session.userId});
      });
      tellTabsIfCountChanged(d, "m", messageCount(d));
    }
  },
  message_read: function(nUri, threadId, time, messageId) {
    api.log("[socket:message_read]", nUri, threadId, time);
    var d = pageData[nUri];
    if (!d || !d.lastMessageRead || new Date(d.lastMessageRead[threadId] || 0) < new Date(time)) {
      markNoticesVisited("message", nUri, messageId, time, "/messages/" + threadId);
      if (d) {
        d.lastMessageRead[threadId] = time;
        d.tabs.forEach(function(tab) {
          api.tabs.emit(tab, "thread_info", {thread: d.threads.filter(hasId(threadId))[0], read: d.lastMessageRead[threadId]});
        });
        tellTabsIfCountChanged(d, "m", messageCount(d));
      }
      tellTabsNoticeCountIfChanged();
    }
  },
};

// ===== Handling messages from content scripts or other extension pages

api.port.on({
  get_keeps: searchOnServer,
  get_chatter: function(data, respond) {
    api.log("[get_chatter]", data.ids);
    ajax("GET", "/search/chatter", {ids: data.ids.join(".")}, respond);
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
      isPrivate: data.how == "private"};
    postBookmarks(function(f) {f([bm])}, "HOVER_KEEP");
    pageData[tab.nUri].tabs.forEach(function(tab) {
      setIcon(tab, data.how);
      api.tabs.emit(tab, "kept", {kept: data.how});
    });
  },
  unkeep: function(_, _, tab) {
    api.log("[unkeep]", tab.url);
    delete (pageData[tab.nUri] || {}).kept;
    ajax("POST", "/bookmarks/remove", {url: tab.url}, function(o) {
      api.log("[unkeep] response:", o);
    });
    pageData[tab.nUri].tabs.forEach(function(tab) {
      setIcon(tab, false);
      api.tabs.emit(tab, "kept", {kept: null});
    });
  },
  set_private: function(priv, _, tab) {
    api.log("[setPrivate]", tab.url, priv);
    ajax("POST", "/bookmarks/private", {url: tab.url, private: priv}, function(o) {
      api.log("[setPrivate] response:", o);
    });
    pageData[tab.nUri].tabs.forEach(function(tab) {
      api.tabs.emit("kept", {kept: priv ? "private" : "public"});
    });
  },
  keeper_shown: function(_, _, tab) {
    (pageData[tab.nUri] || {}).shown = true;  // server already notified via event log
  },
  follow: function(data, respond, tab) {
    ajax("POST", "/comments/" + (data ? "follow" : "unfollow"), {url: tab.url}, function(o) {
      api.log("[follow] resp:", o);
    });
  },
  suppress_on_site: function(data, _, tab) {
    ajax("POST", "/users/slider/suppress", {url: tab.url, suppress: data});
    pageData[tab.nUri].neverOnSite = !!data;
  },
  get_suppressed: function(_, respond, tab) {
    respond(pageData[tab.nUri].neverOnSite);
  },
  log_event: function(data) {
    logEvent.apply(null, data);
  },
  post_comment: function(data, respond) {
    api.log("[post_comment]", data);
    ajax("POST", "/comments", data, function(o) {
      api.log("[post_comment] resp:", o);
      respond(o);
    });
  },
  send_message: function(data, respond) {
    api.log("[send_message]", data);
    ajax("POST", "/messages", data, function(o) {
      api.log("[send_message] resp:", o);
      respond(o);
    });
  },
  send_reply: function(data, respond) {
    api.log("[send_reply]", data);
    var id = data.threadId;
    delete data.threadId;
    ajax("POST", "/messages/" + id, data, function(o) {
      api.log("[send_reply] resp:", o);
      respond(o);
    });
  },
  delete_comment: function(id, respond) {
    ajax("POST", "/comments/" + id + "/remove", function(o) {
      api.log("[deleteComment] response:", o);
      respond(o);
    });
  },
  set_comment_read: function(o, _, tab) {
    var d = pageData[tab.nUri];
    if (!d || new Date(o.time) > new Date(d.lastCommentRead)) {
      markNoticesVisited("comment", tab.nUri, o.id, o.time);
      if (d) {
        d.lastCommentRead = o.time;
        tellTabsIfCountChanged(d, "c", commentCount(d));  // tabs at this uri
      }
      tellTabsNoticeCountIfChanged();  // visible tabs
      socket.send(["set_comment_read", o.id]);
    }
  },
  set_message_read: function(o, _, tab) {
    var d = pageData[tab.nUri];
    if (!d || new Date(o.time) > new Date(d.lastMessageRead[o.threadId] || 0)) {
      markNoticesVisited("message", tab.nUri, o.messageId, o.time, "/messages/" + o.threadId);
      if (d) {
        d.lastMessageRead[o.threadId] = o.time;
        tellTabsIfCountChanged(d, "m", messageCount(d));  // tabs at this uri
      }
      tellTabsNoticeCountIfChanged();  // visible tabs
      socket.send(["set_message_read", o.messageId]);
    }
  },
  comments: function(_, respond, tab) {
    var d = pageData[tab.nUri];
    if (d) d.on2(function() {
      respond(d.comments);
    });
  },
  threads: function(_, respond, tab) {
    var d = pageData[tab.nUri];
    if (d) d.on2(function() {
      respond({threads: d.threads, read: d.lastMessageRead});
    });
  },
  thread: function(data, respond, tab) {  // data.id may be id of any message (not necessarily parent)
    var d = pageData[tab.nUri];
    if (d) d.on2(function() {
      var th = d.threads.filter(function(t) {return t.id == data.id || t.messageTimes[data.id]})[0];
      if (th && d.messages[th.id]) {
        if (data.respond) {
          respond({id: th.id, messages: d.messages[th.id]});
        }
      } else {
        var id = (th || data).id;
        socket.send(["get_thread", id]);
        if (data.respond) {
          (d.threadCallbacks = d.threadCallbacks || []).push({id: id, respond: respond});
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
      respond({
        notifications: notifications.slice(0, NOTIFICATION_BATCH_SIZE),
        newIdxs: newNotificationIdxs,
        timeLastSeen: timeNotificationsLastSeen.toISOString()});
    }
  },
  old_notifications: function(timeStr, respond) {
    var time = new Date(timeStr);
    var n = notifications.length, oldest = notifications[n-1];
    if (new Date(oldest.time) < time || haveAllNotifications) {
      for (var i = n - 1; i && new Date(notifications[i-1].time) < time; i--);
      respond(notifications.slice(i, i + NOTIFICATION_BATCH_SIZE));
    } else {
      socket.send(["get_old_notifications", timeStr, NOTIFICATION_BATCH_SIZE], function(arr) {
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
      identifyNewNotices();
      tellTabsNoticeCountIfChanged();
      socket.send(["set_last_notify_read_time", t]);
    }
  },
  session: function(_, respond) {
    respond(session);
  },
  get_friends: function(_, respond) {
    respond(friends);
  },
  open_deep_link: function(data, _, tab) {
    var uriData = pageData[data.nUri];
    if (uriData) {
      var tab = tab.nUri == data.nUri ? tab : uriData.tabs[0];
      if (tab.ready) {
        api.tabs.emit(tab, "open_to", {trigger: "deepLink", locator: data.locator});
      } else {
        createDeepLinkListener(data.locator, tab.id);
      }
      api.tabs.select(tab.id);
    } else {
      api.tabs.open(data.nUri, function(tabId) {
        createDeepLinkListener(data.locator, tabId);
      });
    }
  },
  add_deep_link_listener: function(locator, _, tab) {
    createDeepLinkListener(locator, tab.id);
  },
  report_error: function(data, _, tag) {
    reportError(data.message, data.url, data.lineNo);
  }
});

function insertNewNotification(n) {
  if (!notifications) return false;
  var time = new Date(n.time);
  for (var i = 0; i < notifications.length; i++) {
    if (new Date(notifications[i].time) <= time) {
      if (notifications[i].id == n.id) return false;
      break;
    }
  }
  notifications.splice(i, 0, n);

  if (n.state != "visited") {  // may have been visited before arrival
    var d = pageData[n.details.page];
    var timeLastRead =
      n.category == "comment" ? d && d.lastCommentRead :
      n.category == "message" ? d && d.lastMessageRead[n.details.locator.split("/")[2]] : 0;
    if (new Date(n.details.createdAt) <= new Date(timeLastRead || 0)) {
      n.state = "visited";
    }
  }

  numNotificationsNotVisited += n.state != "visited";
  if (n.details.subsumes) {
    for (i++; i < notifications.length; i++) {
      var n2 = notifications[i];
      if (n2.id == n.details.subsumes) {
        notifications.splice(i, 1);
        numNotificationsNotVisited -= n2.state != "visited";
        break;
      }
    }
  }
  return true;
}

function identifyNewNotices() {
  newNotificationIdxs.length = 0;
  if (!notifications) return;
  for (var i = 0; i < notifications.length; i++) {
    if (new Date(notifications[i].time) <= timeNotificationsLastSeen) {
      break;
    } else if (notifications[i].state != "visited") {
      newNotificationIdxs.push(i);
    }
  }
}

// id is of last read comment/message. locator not passed in the comments case.
function markNoticesVisited(category, nUri, id, timeStr, locator) {
  var time = new Date(timeStr);
  notifications.forEach(function(n, i) {
    if (n.details.page == nUri &&
        n.category == category &&
        (!locator || n.details.locator == locator) &&
        (n.details.id == id || new Date(n.time) <= time) &&
        n.state != "visited") {
      n.state = "visited";
      numNotificationsNotVisited--;
      var j = newNotificationIdxs.indexOf(i);
      if (~j) newNotificationIdxs.splice(j, 1);
    }
  });
  tabsShowingNotificationsPane.forEach(function(tab) {
    api.tabs.emit(tab, "notifications_visited", {category: category, nUri: nUri, time: timeStr, locator: locator});
  });
}

function createDeepLinkListener(locator, tabId) {
  var createdTime = new Date;
  api.tabs.on.ready.add(function deepLinkListener(tab) {
    if (new Date - createdTime > 15000) {
      api.tabs.on.ready.remove(deepLinkListener);
      api.log("[createDeepLinkListener] Listener timed out.");
      return;
    }
    if (tab.id == tabId) {
      // uncomment second clause below to develop /r/ page using production deep links
      var hasForwarded = !(new RegExp("^" + webBaseUri() + "/r/", "").test(tab.url)) /* && tab.url.indexOf("dev.ezkeep.com") < 0 */;
      if (hasForwarded) {
        api.log("[createDeepLinkListener] Sending deep link to tab " + tab.id, locator);
        api.tabs.emit(tab, "open_to", {trigger: "deepLink", locator: locator});
        api.tabs.on.ready.remove(deepLinkListener);
      }
    }
  });
}

function initTab(tab, d) {  // d is pageData[tab.nUri]
  api.log("[initTab]", tab.id, "inited:", tab.inited);

  api.tabs.emit(tab, "counts", d.counts);
  if (tab.inited) return;
  tab.inited = true;

  if (ruleSet.rules.message && d.counts.m) {  // open immediately to unread message(s)
    var ids = unreadThreadIds(d.threads, d.lastMessageRead);
    api.tabs.emit(tab, "open_to", {trigger: "message", locator: "/messages" + (ids.length > 1 ? "" : "/" + ids[0])});
    ids.forEach(function(id) {
      socket.send(["get_thread", id]);
    });

  } else if (ruleSet.rules.comment && d.counts.c && !d.neverOnSite) {  // open immediately to unread comment(s)
    api.tabs.emit(tab, "open_to", {trigger: "comment", locator: "/comments"});

  } else if (!d.kept && !d.neverOnSite && (!d.sensitive || !ruleSet.rules.sensitive)) {  // auto-engagement
    var url = tab.url;
    if (ruleSet.rules.url && urlPatterns.some(function(re) {return re.test(url)})) {
      api.log("[initTab]", tab.id, "restricted");
    } else if (ruleSet.rules.shown && d.shown) {
      api.log("[initTab]", tab.id, "shown before");
    } else {
      if (api.prefs.get("showSlider")) {
        api.tabs.emit(tab, "scroll_rule", ruleSet.rules.scroll);
      }
      tab.autoShowSec = (ruleSet.rules.focus || [])[0];
      if (tab.autoShowSec != null && api.tabs.isFocused(tab)) {
        scheduleAutoShow(tab);
      }

      if (d.keepers.length) {
        api.tabs.emit(tab, "keepers", {keepers: d.keepers, otherKeeps: d.otherKeeps});
      }
    }
  }
}

function commentCount(d) {  // comments only count as unread if by a friend
  var t = new Date(d.lastCommentRead || 0);
  return d.comments.filter(function(c) {return friendsById[c.user.id] && new Date(c.createdAt) > t}).length;
}

function messageCount(d) {
  var n = 0;
  for (var i = 0; i < d.threads.length; i++) {
    var th = d.threads[i], thReadTime = new Date(d.lastMessageRead[th.id] || 0);
    for (var id in th.messageTimes) {
      if (new Date(th.messageTimes[id]) > thReadTime) {
        n++;
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
      api.tabs.emit(tab, "counts", d.counts);
    }
  });
}

function tellTabsIfCountChanged(d, key, count) {
  if (d.counts[key] != count) {
    d.counts[key] = count;
    d.counts.n = numNotificationsNotVisited;
    d.tabs.forEach(function(tab) {
      api.tabs.emit(tab, "counts", d.counts);
    });
  }
}

function searchOnServer(request, respond) {
  logEvent("search", "newSearch", {query: request.query, filter: request.filter});

  if (!session) {
    api.log("[searchOnServer] no session");
    respond({});
    return;
  }

  ajax("GET", "/search", {
      q: request.query,
      f: request.filter === "a" ? null : request.filter,
      maxHits: request.lastUUID ? 5 : api.prefs.get("maxResults"),
      lastUUID: request.lastUUID,
      context: request.context,
      kifiVersion: api.version},
    function(resp) {
      api.log("[searchOnServer] response:", resp);
      resp.session = session;
      resp.admBaseUri = admBaseUri();
      resp.showScores = api.prefs.get("showScores");
      respond(resp);
    });
  return true;
}

// kifi icon in location bar
api.icon.on.click.add(function(tab) {
  api.tabs.emit(tab, "button_click");
});

function subscribe(tab) {
  api.log("[subscribe] %i %s %s", tab.id, tab.url, tab.icon);
  if (!tab.icon) {
    api.icon.set(tab, "icons/keep.faint.png");
  }
  var d = pageData[tab.nUri || tab.url];
  if (d && d.seq == socket.seq) {  // no need to ask server again
    if (tab.seq == socket.seq) {  // tab is up-to-date
      if (d.counts) {
        d.counts.n = numNotificationsNotVisited;
        api.tabs.emit(tab, "counts", d.counts);
      }
    } else {
      var tabUpToDate = tab.seq == d.seq;
      finish(tab.nUri || tab.url);
      if (d.hasOwnProperty("kept")) {
        if (!tabUpToDate) {
          setIcon(tab, d.kept);
          sendKept(tab, d);
          if (d.counts) {
            initTab(tab, d);
          } // else wait for uri_2
        }
      } // else wait for uri_1
    }
  } else if (socket) {
    socket.send(["subscribe_uri", tab.url], function(uri) {
      if (api.tabs.get(tab.id).url != tab.url) return;
      d = pageData[uri] = pageData[uri] || new PageData;
      d.seq = socket.seq;
      finish(uri);
    });
  }
  function finish(uri) {
    tab.nUri = uri;
    tab.seq = d.seq;
    for (var i = 0; i < d.tabs.length; i++) {
      if (d.tabs[i].id == tab.id) {
        d.tabs.splice(i--, 1);
      }
    }
    d.tabs.push(tab);
    api.log("[subscribe:finish]", tab.id);
  }
}

function setIcon(tab, kept) {
  api.log("[setIcon] tab:", tab.id, "kept:", kept);
  api.icon.set(tab, kept ? "icons/kept.png" : "icons/keep.png");
}

function sendKept(tab, d) {
  api.tabs.emit(tab, "kept", {kept: d.kept, hide: d.neverOnSite || (d.sensitive && ruleSet.rules.sensitive)});
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

// ===== Browser event listeners

api.tabs.on.focus.add(function(tab) {
  api.log("#b8a", "[tabs.on.focus] %i %o", tab.id, tab);
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
});

api.tabs.on.ready.add(function(tab) {
  api.log("#b8a", "[tabs.on.ready] %i %o", tab.id, tab);
  logEvent("extension", "pageLoad");
});

api.tabs.on.complete.add(function(tab) {
  api.log("#b8a", "[tabs.on.complete] %i %o", tab.id, tab);
});

api.tabs.on.unload.add(function(tab) {
  api.log("#b8a", "[tabs.on.unload] %i %o", tab.id, tab);
  api.timers.clearTimeout(tab.autoShowTimer);
  delete tab.autoShowTimer;
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
  socket && socket.send(["unsubscribe_uri", tab.nUri || tab.url]);
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
        api.tabs.emit(tab, "auto_show");
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
  return function(o) {return o.id == id};
}

function getId(o) {
  return o.id;
}

function devUriOr(uri) {
  return api.prefs.get("env") === "development" ? "http://dev.ezkeep.com:9000" : uri;
}
var apiBaseUri = devUriOr.bind(0, "https://api.kifi.com");
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

// ===== Session management

var session, socket;

function authenticate(callback) {
  var dev = api.prefs.get("env") === "development";
  if (dev) {
    openFacebookConnect();
  } else {
    startSession(openFacebookConnect);
  }

  function startSession(onFail) {
    ajax("POST", "/kifi/start", {
      installation: getStored("kifi_installation_id"),
      version: api.version},
    function done(data) {
      api.log("[startSession] reason: %s session: %o", api.loadReason, data);
      logEvent("extension", "authenticated");

      session = data;
      socket = api.socket.open(apiBaseUri().replace(/^http/, "ws") + "/ext/ws", socketHandlers, function onConnect() {
        socket.send(["get_last_notify_read_time"]);
        if (!notifications) {
          socket.send(["get_notifications", NOTIFICATION_BATCH_SIZE]);
        } else {
          socket.send(["get_missed_notifications", notifications.length ? notifications[0].time : new Date(0).toISOString()]);
        }
        socket.send(["get_friends"]);  // TODO: optimize seq > 1 case
        if (socket.seq > 1) {  // reconnected
          socket.send(["get_rules", ruleSet.version]);
          api.tabs.eachSelected(subscribe);
        }
      });
      logEvent.catchUp();

      ruleSet = data.rules;
      urlPatterns = compilePatterns(data.patterns);
      store("kifi_installation_id", data.installationId);
      delete session.rules;
      delete session.patterns;
      delete session.installationId;

      callback();

      if (api.loadReason == "install" || dev) {
        postBookmarks(api.bookmarks.getAll, "INIT_LOAD");
      }
    }, function fail(xhr) {
      api.log("[startSession] xhr failed:", xhr);
      if (onFail) onFail();
    });
  }

  function openFacebookConnect() {
    api.log("[openFacebookConnect]");
    var baseUri = webBaseUri();
    api.popup.open({
      name: "kifi-authenticate",
      url: baseUri + "/authenticate/facebook",
      width: 1020,
      height: 530}, {
      navigate: function(url) {
        if (url == baseUri + "/#_=_") {
          api.log("[openFacebookConnect] closing popup");
          this.close();
          startSession();
        }
      }});
  }
}

function deauthenticate() {
  api.log("[deauthenticate]");
  session = null;
  if (socket) {
    socket.close();
    socket = null;
  }
  clearDataCache();
  // TODO: make all page icons faint?
  api.popup.open({
    name: "kifi-deauthenticate",
    url: webBaseUri() + "/session/end",
    width: 200,
    height: 100})
}

// ===== Main, executed upon install (or reinstall), update, re-enable, and browser start

logEvent("extension", "started");

authenticate(function() {
  api.log("[main] authenticated");

  if (api.loadReason == "install") {
    api.log("[main] fresh install");
    var tab = api.tabs.anyAt(webBaseUri() + "/install");
    if (tab) {
      api.tabs.navigate(tab.id, webBaseUri() + "/getting-started");
    } else {
      api.tabs.open(webBaseUri() + "/getting-started");
    }
  }

  api.tabs.eachSelected(subscribe);
});

// Global error logging

function reportError(errMsg, url, lineNo) {
  api.log('Reporting error "%s" in %s line %s', errMsg, url, lineNo);
  if (!api.isPackaged()) {
    // Don't report errors on development (unpacked) extensions
    return;
  }
  ajax("POST", "/error/report", {
    message: 'Error ' + errMsg + ' at ' + url + ' line ' + lineNo
  }, function () {
    api.log('Logged error "%s" in %s line %s', errMsg, url, lineNo);
  });
}

try {
  // TODO: make error handler work correctly in Firefox
  window.onerror = reportError;
} catch (_) {}

