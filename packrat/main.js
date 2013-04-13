var api = api || require("./api");

// ===== Cached data from server

var pageData = {};
var notifications = [];
var notificationsRead = {};
var friends = [];
var friendsById = {};

function clearDataCache() {
  pageData = {};
  notifications.length = 0;
  notificationsRead = {};
  friends.length = 0;
  friendsById = {};
}

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
    uri = uri + (uri.indexOf("?") < 0 ? "?" : "&") + a.join("&").replace(/%20/g, "+");
    data = null;
  }

  api.request(method, uri, data, done, fail);
}

// ===== Event logging

const eventFamilies = {slider:1, search:1, extension:1, account:1, notification:1};

function logEvent(eventFamily, eventName, metaData, prevEvents) {
  if (!eventFamilies[eventFamily]) {
    api.log("#800", "[logEvent] invalid event family:", eventFamily);
    return;
  }
  var ev = {
    installId: getConfigs().kifi_installation_id, // ExternalId[KifiInstallation]
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
  slider_rules: function(rules) {
    api.log("[socket:slider_rules]", rules);
    session.rules = rules;
  },
  url_patterns: function(patterns) {
    api.log("[socket:url_patterns]", patterns);
    session.patterns = patterns;
    compilePatterns(session);
  },
  uri_1: function(uri, o) {
    api.log("[socket:uri_1]", o);
    var d = pageData[uri];
    if (d) {
      d.kept = o.kept;
      d.sensitive = o.sensitive;
      d.tabs.forEach(function(tab) {
        setIcon(tab, d.kept);
      });
    }
  },
  uri_2: function(uri, o) {
    api.log("[socket:uri_2]", o);
    var d = pageData[uri];
    if (d) {
      d.shown = o.shown;
      d.neverOnSite = o.neverOnSite;
      d.keepers = o.keepers || [];
      d.keeps = o.keeps || 0;
      d.following = o.following;
      d.comments = o.comments || [];
      d.threads = o.threads || [];
      d.messages = {};
      d.lastCommentRead = new Date(o.lastCommentRead || 0);
      d.lastMessageRead = {};
      for (var k in o.lastMessageRead) {
        d.lastMessageRead[k] = new Date(o.lastMessageRead);
      }
      d.tabs.forEach(function(tab) {
        initTab(tab, d);
      });
    }
  },
  notification: function(notification) {
    api.log("[socket:notification]", notification);
    api.tabs.eachSelected(function(tab) {
      api.tabs.emit(tab, "show_notification", notification);
    });
    socketHandlers.notifications([notification]);
  },
  notifications: function(arr) {
    api.log("[socket:notifications]", arr);
    var idToNotif = {};
    notifications.concat(arr).forEach(function(n) {
      idToNotif[n.id] = n;
    });
    notifications.length = 0;
    for (var id in idToNotif) {
      var subsumes = idToNotif[id].details.subsumes;
      if (subsumes) {
        delete idToNotif[subsumes];
      }
    }
    for (var id in idToNotif) {
      notifications.push(idToNotif[id]);
    }
    notifications.sort(function(a, b) {
      return new Date(b.time) - new Date(a.time);
    });
    emitNotifications();
  },
  last_notify_read_time: function(t) {
    api.log("[socket:last_notify_read_time]", t);
    notificationsRead.time = new Date(t);
    countUnreadNotifications();
  },
  comment: function(nUri, c) {
    api.log("[socket:comment]", c);
    var d = pageData[nUri];
    if (d && d.comments) {
      d.comments.push(c);
      d.tabs.forEach(function(tab) {
        api.tabs.emit(tab, "comment", c);
      });
    }
  },
  thread: function(th) {
    api.log("[socket:thread]", th);
    var d = pageData[th.uri];
    if (d && d.messages) {
      d.messages[th.id] = th.messages;
      d.tabs.forEach(function(tab) {
        api.tabs.emit(tab, "thread", {id: th.id, messages: th.messages});
      });
    }
  },
  message: function(nUri, th, message) {
    api.log("[socket:message]", nUri, th, message);
    var d = pageData[nUri];
    if (d && d.threads) {
      for (var i = 0, n = d.threads.length; i < n; i++) {
        if (d.threads[i].id == th.id) break;
      }
      if (i < n) {
        d.threads[i] = th;
        var messages = d.messages[th.id];
        if (messages && !messages.some(hasId(message.id))) {  // sent messages come via POST resp and socket
          messages.push(message);
        }
      } else {
        d.threads.push(th);
        d.messages[th.id] = [message];
      }
      d.tabs.forEach(function(tab) {
        api.tabs.emit(tab, "message", {thread: th, message: message});
      });
    }
  }
};

// ===== Handling messages from content scripts or other extension pages

api.port.on({
  log_in: function(_, respond) {
    authenticate(function() {
      respond(session);
    });
    return true;
  },
  log_out: function(_, respond) {
    deauthenticate(respond);
    return true;
  },
  get_keeps: searchOnServer,
  get_chatter: function(data, respond) {
    api.log("[get_chatter]", data.ids);
    ajax("GET", "http://" + getConfigs().server + "/search/chatter", {ids: data.ids.join(".")}, respond);
    return true;
  },
  get_num_mutual_keeps: function(data, respond) {
    api.log("[get_num_mutual_keeps]", data.id);
    ajax("GET", "http://" + getConfigs().server + "/bookmarks/mutual/" + data.id, respond);
    return true;
  },
  add_bookmarks: function(data, respond) {
    getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
      addKeep(info, data, respond);
    });
    return true;
  },
  unkeep: function(_, respond, tab) {
    getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
      removeKeep(info, tab.url, respond);
    });
    return true;
  },
  set_private: function(data, respond, tab) {
    getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
      setPrivate(info, tab.url, data, respond);
    });
    return true;
  },
  follow: function(data, respond, tab) {
    ajax("POST", "http://" + getConfigs().server + "/comments/" + (data ? "follow" : "unfollow"), {url: tab.url}, function(o) {
      api.log("[follow] resp:", o);
    });
  },
  get_prefs: function(_, respond) {
    respond({session: session, prefs: api.prefs.get("env", "showSlider", "maxResults", "showScores")});
  },
  set_prefs: function(data) {
    api.prefs.set(data);
  },
  set_page_icon: function(data, _, tab) {
    setIcon(tab, data);
  },
  init_slider_please: function(_, _, tab) {
    var emission = tab.emitOnReady;
    if (session && emission) {
      api.log("[init_slider_please] %i emitting %s", tab.id, emission[0]);
      api.tabs.emit(tab, emission[0], emission[1]);
      delete tab.emitOnReady;
    }
  },
  get_slider_info: function(data, respond, tab) {
    if (session) {
      getSliderInfo();
    } else {
      authenticate(getSliderInfo);
    }
    return true;

    function getSliderInfo() {
      ajax("GET", "http://" + getConfigs().server + "/users/slider", {url: tab.url}, function(o) {
        o.session = session;
        respond(o);
      });
    }
  },
  get_slider_updates: function(_, respond, tab) {
    ajax("GET", "http://" + getConfigs().server + "/users/slider/updates", {url: tab.url}, respond);
    return true;
  },
  suppress_on_site: function(data, _, tab) {
    ajax("POST", "http://" + getConfigs().server + "/users/slider/suppress", {url: tab.url, suppress: data});
  },
  log_event: function(data) {
    logEvent.apply(null, data);
  },
  get_comments: function(data, respond, tab) {
    ajax("GET", "http://" + getConfigs().server +
      (data.kind == "public" ? "/comments" : "/messages/threads") +
      (data.commentId ? "/" + data.commentId : "?url=" + encodeURIComponent(tab.url)),
      function(o) {
        o.session = session;
        respond(o);
      });
    return true;
  },
  post_comment: function(data, respond, tab) {
    postComment(data, function(o) {
      var d = pageData[tab.nUri];
      if (data.permissions == "public") {
        d.comments.push({
          "id": o.commentId,
          "createdAt": o.createdAt,
          "text": data.text,
          "user": {
            "id": session.userId,
            "firstName": session.name,
            "lastName": "",
            "facebookId": session.facebookId
          }});
      } else if (data.permissions == "message") {
        var threadId = data.parent;
        d.threads.forEach(function(th) {
          if (th.id == threadId) {
            th.messageCount++;
            th.messageTimes[o.message.id] = o.message.createdAt;
            th.lastCommentedAt = o.message.createdAt;
            th.digest = o.message.text;
          }
        });
        if (d.messages[threadId]) {
          d.messages[threadId].push(o.message);
        } else {
          d.messages[threadId] = [o.message];
        }
      }
      respond(o);
    });
    return true;
  },
  delete_comment: function(id, respond) {
    ajax("POST", "http://" + getConfigs().server + "/comments/" + id + "/remove", function(o) {
      api.log("[deleteComment] response:", o);
      respond(o);
    });
    return true;
  },
  set_comment_read: function(o, _, tab) {
    var d = pageData[tab.nUri], time = new Date(o.time);
    if (!d || time > d.lastCommentRead) {
      if (d) d.lastCommentRead = time;
      socket.send(["set_comment_read", o.id]);
    }
  },
  set_message_read: function(o, _, tab) {
    var d = pageData[tab.nUri], time = new Date(o.time);
    if (!d || time > (d.lastMessageRead[o.threadId] || 0)) {
      if (d) d.lastMessageRead[o.threadId] = time;
      socket.send(["set_message_read", o.messageId]);
    }
  },
  comments: function(_, _, tab) {
    var d = pageData[tab.nUri];
    if (d && d.comments) {
      api.tabs.emit(tab, "comments", d.comments);
    }
  },
  threads: function(_, _, tab) {
    var d = pageData[tab.nUri];
    if (d && d.threads) {
      api.tabs.emit(tab, "threads", d.threads);
    }
  },
  thread: function(id, _, tab) {
    var d = pageData[tab.nUri];
    if (d && d.messages[id]) {
      api.tabs.emit(tab, "thread", {id: id, messages: d.messages[id]});
    } else {
      socket.send(["get_thread", id]);
    }
  },
  notifications: function(howMany, tab) {
    if (howMany > notifications.length) {
      var oldest = (notifications[notifications.length-1] || {}).time;
      socket.send(["get_notifications", howMany - notifications.length, oldest]);
    } else {
      emitNotifications();
    }
  },
  notifications_read: function(time) {
    socket.send(["set_last_notify_read_time", time]);
  },
  session: function(_, respond) {
    respond(session);
  },
  get_friends: function(_, respond) {
    respond(friends);
  },
  add_deep_link_listener: function(data, respond, tab) {
    createDeepLinkListener(data, tab.id, respond);
    return true;
  }
});

function emitNotifications() {
  countUnreadNotifications();
  api.tabs.eachSelected(function(tab) {
    api.tabs.emit(tab, "notifications", {
      notifications: notifications,
      numUnread: notificationsRead.unread,
      lastRead: notificationsRead.time
    });
  });
}

function createDeepLinkListener(link, linkTabId, respond) {
  var createdTime = new Date;
  api.tabs.on.ready.add(function deepLinkListener(tab) {
    if (new Date - createdTime > 15000) {
      api.tabs.on.ready.remove(deepLinkListener);
      api.log("[createDeepLinkListener] Listener timed out.");
      return;
    }
    if (linkTabId == tab.id) {
      var hasForwarded = tab.url.indexOf(getConfigs().server + "/r/") < 0 && tab.url.indexOf("dev.ezkeep.com") < 0;
      if (hasForwarded) {
        api.log("[createDeepLinkListener] Sending deep link to tab " + tab.id, link.locator);
        api.tabs.emit(tab, "open_slider_to", {trigger: "deepLink", locator: link.locator, metro: session.experiments.indexOf("metro") >= 0});
        api.tabs.on.ready.remove(deepLinkListener);
        return;
      }
    }
  });
}

function initTab(tab, o) {  // o is pageData[tab.nUri]
  var metro = session.experiments.indexOf("metro") >= 0;
  var unread = findUnread(o.threads, o.lastMessageRead);
  o.counts = {
    unreadNotices: notifications.filter(function(n) {return new Date(n.time) > notificationsRead.time}).length,
    numComments: o.comments.length,
    unreadComments: o.comments.filter(function(c) {return friendsById[c.user.id] && new Date(c.createdAt) > o.lastCommentRead}).length,
    numMessages: o.threads.reduce(function(n, t) {return n + t.messageCount}, 0),
    unreadMessages: unread.messages};
  var data = {
    metro: metro,
    kept: !!o.kept,
    private: o.kept == "private",
    keepers: o.keepers,
    keeps: o.keeps,
    otherKeeps: o.keeps - o.keepers.length - (o.kept == "public" ? 1 : 0),
    sensitive: !!o.sensitive,
    neverOnSite: !!o.neverOnSite,
    counts: o.counts};

  if (session.rules.rules.message && unread.messages) {
    data.trigger = "message";
    data.locator = "/messages" + (unread.threads.length > 1 ? "" : "/" + unread.threads[0]);
    unread.threads.forEach(function(id) {
      socket.send(["get_thread", id]);
    });
  } else if (session.rules.rules.comment && data.counts.unreadComments && (metro || !o.neverOnSite)) {
    data.trigger = "comment";
    data.locator = "/comments";
  } else if (!o.kept && (metro || !o.neverOnSite) && (!o.sensitive || !session.rules.rules.sensitive)) {
    var url = tab.url;
    if (session.rules.rules.url && session.patterns.some(function(re) {return re.test(url)})) {
      api.log("[initTab]", tab.id, "restricted");
    } else if (session.rules.rules.shown && o.shown) {
      api.log("[initTab]", tab.id, "shown before");
    } else {
      if (api.prefs.get("showSlider")) {
        data.rules = { // only the relevant ones
          scroll: session.rules.rules.scroll,
          viewport: session.rules.rules.viewport};
      }
      tab.autoShowSec = (session.rules.rules[!metro && o.keepers ? "friendKept" : "focus"] || [])[0];
      if (tab.autoShowSec != null && api.tabs.isFocused(tab)) {
        scheduleAutoShow(tab);
      }
    }
  }

  api.log("[initTab] %i %o", tab.id, data);
  if (tab.ready) {
    api.tabs.emit(tab, "init_slider", data);
  } else {
    tab.emitOnReady = ["init_slider", data];
  }
}

function findUnread(threads, readTimes) {
  var unread = {threads: [], messages: 0};
  for (var i = 0; i < threads.length; i++) {
    var th = threads[i], thReadTime = new Date(readTimes[th.id] || 0), thUnread;
    for (var id in th.messageTimes) {
      if (new Date(th.messageTimes[id]) > thReadTime) {
        thUnread = true;
        unread.messages++;
      }
    }
    if (thUnread) {
      unread.threads.push(th.id);
    }
  }
  return unread;
}

function countUnreadNotifications() {
  for (var n = 0; n < notifications.length; n++) {
    if (new Date(notifications[n].time) <= notificationsRead.time) break;
  }
  notificationsRead.unread = n;
  api.tabs.eachSelected(function(tab) {
    var d = pageData[tab.nUri];
    if (d && d.counts && d.counts.unreadNotices != n) {
      d.counts.unreadNotices = n;
      api.tabs.emit(tab, "counts", d.counts);
    }
  });
}

// Finds KiFi bookmark folder by id (if provided) or by name in the Bookmarks Bar,
// or else creates it there. Ensures that it has "public" and "private" subfolders.
// Passes an object with the three folder ids to the callback.
function getBookmarkFolderInfo(keepItBookmarkId, callback) {
  api.log("[getBookmarkFolderInfo]");

  if (keepItBookmarkId) {
    api.bookmarks.get(keepItBookmarkId, function(bm) {
      if (bm) {
        // We created this bookmark folder. We continue to use it even if user has moved it or renamed it.
        ensurePublicPrivate(bm);
      } else {
        findOrCreateKeepIt();
      }
    });
  } else {
    findOrCreateKeepIt();
  }

  function findOrCreateKeepIt() {
    api.bookmarks.getBarFolder(function(bar) {
      api.bookmarks.getChildren(bar.id, function(bm) {
        var keepIt = bm.filter(function(bm) { return bm.title == "KeepIt" });
        if (keepIt.length) {
          ensurePublicPrivate(keepIt[0]);
        } else {
          api.bookmarks.createFolder(bar.id, "KeepIt", function(bm) {
            ensurePublicPrivate(bm);
          });
        }
      });
    });
  }

  function ensurePublicPrivate(keepIt) {
    api.bookmarks.getChildren(keepIt.id, function(children) {
      var bm = children.filter(function(bm) { return bm.title == "public" });
      if (bm.length) {
        ensurePrivate({keepItId: keepIt.id, publicId: bm[0].id}, children);
      } else {
        api.bookmarks.createFolder(keepIt.id, "public", function(bm) {
          ensurePrivate({keepItId: keepIt.id, publicId: bm.id}, children);
        });
      }
    });
  }

  function ensurePrivate(info, children) {
    var bm = children.filter(function(bm) { return bm.title == "private" });
    if (bm.length) {
      info.privateId = bm[0].id;
      done(info);
    } else {
      api.bookmarks.createFolder(info.keepItId, "private", function(bm) {
        info.privateId = bm.id;
        done(info);
      });
    }
  }

  function done(info) {
    api.log("[getBookmarkFolderInfo] done");
    callback(info);
  }
}

function addKeep(bmInfo, req, respond) {
  api.log("[addKeep] private: " + !!req.private + ", title: " + req.title);
  api.bookmarks.create(bmInfo[req.private ? "privateId" : "publicId"], req.title, req.url, function(bm) {
    respond(bm);
    bm.isPrivate = !!req.private;
    postBookmarks(function(f) {f([bm])}, "HOVER_KEEP");
  });
}

function removeKeep(bmInfo, url, respond) {
  api.log("[removeKeep] url:", url);

  if (!session) {
    api.log("[removeKeep] no session");
    return;
  }

  api.bookmarks.search(url, function(bm) {
    bm.forEach(function(bm) {
      if (bm.url === url && (bm.parentId == bmInfo.publicId || bm.parentId == bmInfo.privateId)) {
        api.bookmarks.remove(bm.id);
      }
    });
  });

  ajax("POST", "http://" + getConfigs().server + "/bookmarks/remove", {url: url}, function(o) {
    api.log("[removeKeep] response:", o);
    respond(o);
  });
}

function setPrivate(bmInfo, url, priv, respond) {
  api.log("[setPrivate]", url, priv);

  var newParentId = priv ? bmInfo.privateId : bmInfo.publicId;
  var oldParentId = priv ? bmInfo.publicId : bmInfo.privateId;
  api.bookmarks.search(url, function(bm) {
    bm.forEach(function(bm) {
      if (bm.url === url && bm.parentId == oldParentId) {
        api.bookmarks.move(bm.id, newParentId);
      }
    });
  });

  ajax("POST", "http://" + getConfigs().server + "/bookmarks/private", {url: url, private: priv}, function(o) {
    api.log("[setPrivate] response:", o);
    respond(o);
  });
}

function postComment(request, respond) {
  api.log("[postComment] req:", request);
  ajax("POST", "http://" + getConfigs().server + "/comments/add", {
      url: request.url,
      title: request.title,
      text: request.text,
      permissions: request.permissions,
      parent: request.parent,
      recipients: request.recipients},
    function(o) {
      api.log("[postComment] resp:", o);
      o.session = session;
      respond(o);
    });
}

function searchOnServer(request, respond) {
  logEvent("search", "newSearch", {query: request.query, filter: request.filter});

  if (!session) {
    api.log("[searchOnServer] no session");
    respond({});
    return;
  }

  var config = getConfigs();
  ajax("GET", "http://" + config.server + "/search", {
      q: request.query,
      f: request.filter === "a" ? null : request.filter,
      maxHits: request.lastUUID ? 5 : api.prefs.get("maxResults"),
      lastUUID: request.lastUUID,
      context: request.context,
      kifiVersion: api.version},
    function(resp) {
      api.log("[searchOnServer] response:", resp);
      resp.session = session;
      resp.server = config.server;
      resp.showScores = api.prefs.get("showScores");
      respond(resp);
    });
  return true;
}

// Kifi icon in location bar
api.icon.on.click.add(function(tab) {
  api.tabs.emit(tab, "button_click");
});

function subscribe(tab) {
  if (!tab.icon) {
    api.log("[subscribe] %i %s %s", tab.id, tab.url, tab.icon);
    api.icon.set(tab, "icons/keep.faint.png");
    var d = pageData[tab.url];
    if (d && d.counts) {  // optimization: page url is normalized and page is open elsewhere
      finish(tab.url);
      setIcon(tab, d.kept);
      initTab(tab, d);
    } else if (socket) {
      socket.send(["subscribe_uri", tab.url], function(uri) {
        d = pageData[uri] = pageData[uri] || {tabs: []};
        finish(uri);
      });
    }
  }
  function finish(uri) {
    tab.nUri = uri;
    for (var i = 0; i < d.tabs.length; i++) {
      if (d.tabs[i].id == tab.id) {
        d.tabs.splice(i--, 1);
      }
    }
    d.tabs.push(tab);
    api.log("[subscribe:finish] %i page data: %o", tab.id, d);
  }
}

function setIcon(tab, kept) {
  api.log("[setIcon] tab:", tab.id, "kept:", kept);
  api.icon.set(tab, kept ? "icons/kept.png" : "icons/keep.png");
}

function postBookmarks(supplyBookmarks, bookmarkSource) {
  api.log("[postBookmarks]");
  supplyBookmarks(function(bookmarks) {
    api.log("[postBookmarks] bookmarks:", bookmarks);
    ajax("POST", "http://" + getConfigs().server + "/bookmarks/add", {
        bookmarks: bookmarks,
        source: bookmarkSource},
      function(o) {
        api.log("[postBookmarks] resp:", o);
      });
  });
}

// ===== Browser event listeners

api.tabs.on.focus.add(function(tab) {
  api.log("#b8a", "[tabs.on.focus] %i %o", tab.id, tab);
  if (tab.autoShowSec != null && !tab.autoShowTimer) {
    scheduleAutoShow(tab);
  } else {
    subscribe(tab);
  }
  var d = pageData[tab.nUri];
  if (d && d.counts) {
    api.tabs.emit(tab, "counts", d.counts);
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

  var emission = tab.emitOnReady;
  if (emission) {
    api.log("[tabs.on.ready] emitting: %i %o", tab.id, emission);
    api.tabs.emit(tab, emission[0], emission[1]);
    delete tab.emitOnReady;
  }
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
    tab.autoShowTimer = api.timers.setTimeout(function() {
      delete tab.autoShowSec;
      delete tab.autoShowTimer;
      if (api.prefs.get("showSlider")) {
        if (tab.ready) {
          api.log("[scheduleAutoShow:1] fired for tab:", tab.id);
          api.tabs.emit(tab, "auto_show");
        } else {
          api.log("[scheduleAutoShow:1] fired but tab not ready:", tab.id);
          tab.emitOnReady = ["auto_show"];
        }
      }
    }, tab.autoShowSec * 1000);
  }
}

function compilePatterns(o) {
  for (var i = 0; i < o.patterns.length; i++) {
    o.patterns[i] = new RegExp(o.patterns[i], "");
  }
  return o;
}

function hasId(id) {
  return function(o) {return o.id == id};
}

function getFullyQualifiedKey(key) {
  return (api.prefs.get("env") || "production") + "_" + key;
}

function removeFromConfigs(key) {
  delete api.storage[getFullyQualifiedKey(key)];
}

function setConfigs(key, value) {
  var prev = api.storage[getFullyQualifiedKey(key)];
  if (value != null && prev !== String(value)) {
    api.log("[setConfigs]", key, " = ", value, " (was ", prev, ")");
    api.storage[getFullyQualifiedKey(key)] = value;
  }
}

function getConfigs() {
  return {
    "server": api.prefs.get("env") === "development" ? "dev.ezkeep.com:9000" : "keepitfindit.com",
    "kifi_installation_id": api.storage[getFullyQualifiedKey("kifi_installation_id")],
    "bookmark_id": api.storage[getFullyQualifiedKey("bookmark_id")]};
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
  var config = getConfigs(), dev = api.prefs.get("env") === "development";
  if (dev) {
    openFacebookConnect();
  } else {
    startSession(openFacebookConnect);
  }

  function startSession(onFail) {
    ajax("POST", "http://" + config.server + "/kifi/start", {
      installation: config.kifi_installation_id,
      version: api.version,
      agent: api.browserVersion},
    function done(data) {
      api.log("[startSession] reason: %s session: %o", api.loadReason, data);
      logEvent("extension", "authenticated");

      session = compilePatterns(data);
      socket = api.socket.open(
        (api.prefs.get("env") === "development" ? "ws://" : "wss://") + getConfigs().server + "/ext/ws",
        socketHandlers);
      socket.send(["get_notifications", 10]);
      socket.send(["get_last_notify_read_time"]);
      socket.send(["get_friends"]);
      logEvent.catchUp();

      setConfigs("kifi_installation_id", data.installationId);

      // Locate or create KeepIt bookmark folder.
      getBookmarkFolderInfo(config.bookmark_id, function(info) {
        setConfigs("bookmark_id", info.keepItId);
      });

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
    api.popup.open({
      name: "kifi-authenticate",
      url: "http://" + config.server + "/authenticate/facebook",
      width: 1020,
      height: 530}, {
      navigate: function(url) {
        if (url == "http://" + config.server + "/#_=_") {
          api.log("[openFacebookConnect] closing popup");
          this.close();
          startSession();
        }
      }});
  }
}

function deauthenticate(callback) {
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
    url: "http://" + getConfigs().server + "/session/end",
    width: 200,
    height: 100})
  callback();
}

// ===== Main (executed upon install, reinstall, update, reenable, and browser start)

logEvent("extension", "started");

authenticate(function() {
  api.log("[main] authenticated");
  api.tabs.eachSelected(subscribe);
});
