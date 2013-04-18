var api = api || require("./api");

// ===== Cached data from server

var pageData = {};
var notifications = [];
var notificationsRead = {};
var friends = [];
var friendsById = {};
var rules = {};
var urlPatterns = [];

function clearDataCache() {
  pageData = {};
  notifications = [];
  notificationsRead = {};
  friends = [];
  friendsById = {};
  rules = {};
  urlPatterns = [];
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
    uri += (~uri.indexOf("?") ? "&" : "?") + a.join("&").replace(/%20/g, "+");
    data = null;
  }

  api.request(method, "http://" + getServer() + uri, data, done, fail);
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
  slider_rules: function(o) {
    api.log("[socket:slider_rules]", o);
    rules = o.rules;
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
      });
    }
  },
  uri_2: function(uri, o) {
    api.log("[socket:uri_2]", o);
    var d = pageData[uri];
    if (d) {
      d.shown = o.shown;
      d.keepers = o.keepers || [];
      d.keeps = o.keeps || 0;
      d.following = o.following;
      d.comments = o.comments || [];
      d.threads = o.threads || [];
      d.messages = {};
      d.lastCommentRead = new Date(o.lastCommentRead || 0);
      d.lastMessageRead = {};
      for (var k in o.lastMessageRead) {
        d.lastMessageRead[k] = new Date(o.lastMessageRead[k]);
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
      tellTabsIfCountChanged(d, "c", commentCount(d));
    }
  },
  comment_read: function(nUri, time) {
    api.log("[socket:comment_read]", nUri, time);
    var d = pageData[nUri];
    if (d) {
      d.lastCommentRead = new Date(time);
      tellTabsIfCountChanged(d, "c", commentCount(d));
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
          messages.push(message);  // should we maintain chronological order?
        }
      } else {
        d.threads.push(th);  // should we maintain chronological order?
        d.messages[th.id] = [message];
      }
      if (message.user.id == session.userId) {
        var t = new Date(message.createdAt);
        if (t > (d.lastMessageRead[th.id] || 0)) {
          d.lastMessageRead[th.id] = t;
        }
      }
      d.tabs.forEach(function(tab) {
        api.tabs.emit(tab, "message", {thread: th, message: message, read: d.lastMessageRead[th.id]});
      });
      tellTabsIfCountChanged(d, "m", messageCount(d));
    }
  },
  message_read: function(nUri, threadId, time) {
    api.log("[socket:message_read]", nUri, threadId, time);
    var d = pageData[nUri];
    if (d) {
      d.lastMessageRead[threadId] = new Date(time);
      d.tabs.forEach(function(tab) {
        api.tabs.emit(tab, "thread_info", {thread: d.threads.filter(hasId(threadId))[0], read: d.lastMessageRead[threadId]});
      });
      tellTabsIfCountChanged(d, "m", messageCount(d));
    }
  },
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
    ajax("GET", "/search/chatter", {ids: data.ids.join(".")}, respond);
    return true;
  },
  get_num_mutual_keeps: function(data, respond) {
    api.log("[get_num_mutual_keeps]", data.id);
    ajax("GET", "/bookmarks/mutual/" + data.id, respond);
    return true;
  },
  add_bookmarks: function(data, respond) {
    getBookmarkFolderInfo(getStored("bookmark_id"), function(info) {
      addKeep(info, data, respond);
    });
    return true;
  },
  unkeep: function(_, respond, tab) {
    getBookmarkFolderInfo(getStored("bookmark_id"), function(info) {
      removeKeep(info, tab.url, respond);
    });
    return true;
  },
  set_private: function(data, respond, tab) {
    getBookmarkFolderInfo(getStored("bookmark_id"), function(info) {
      setPrivate(info, tab.url, data, respond);
    });
    return true;
  },
  follow: function(data, respond, tab) {
    ajax("POST", "/comments/" + (data ? "follow" : "unfollow"), {url: tab.url}, function(o) {
      api.log("[follow] resp:", o);
    });
  },
  get_prefs: function(_, respond) {
    respond({session: session, prefs: api.prefs.get("env", "showSlider", "maxResults", "showScores")});
  },
  set_prefs: function(data) {
    api.prefs.set(data);
  },
  set_env: function(env) {
    api.prefs.set("env", env);
    chrome.runtime.reload();
  },
  set_page_icon: function(data, _, tab) {
    setIcon(tab, data);
  },
  suppress_on_site: function(data, _, tab) {
    ajax("POST", "/users/slider/suppress", {url: tab.url, suppress: data});
  },
  log_event: function(data) {
    logEvent.apply(null, data);
  },
  get_comments: function(data, respond, tab) {
    ajax("GET",
      (data.kind == "public" ? "/comments" : "/messages/threads") +
      (data.commentId ? "/" + data.commentId : "?url=" + encodeURIComponent(tab.url)),
      function(o) {
        o.session = session;
        respond(o);
      });
    return true;
  },
  post_comment: function(data, respond, tab) {
    api.log("[postComment]", data);
    ajax("POST", "/comments/add", {
        url: data.url,
        title: data.title,
        text: data.text,
        permissions: data.permissions,
        parent: data.parent,
        recipients: data.recipients},
      function(o) {
        api.log("[postComment] resp:", o);
        respond(o);
      });
    return true;
  },
  delete_comment: function(id, respond) {
    ajax("POST", "/comments/" + id + "/remove", function(o) {
      api.log("[deleteComment] response:", o);
      respond(o);
    });
    return true;
  },
  set_comment_read: function(o, _, tab) {
    var d = pageData[tab.nUri], time = new Date(o.time);
    if (!d || time > d.lastCommentRead) {
      if (d) {
        d.lastCommentRead = time;
        tellTabsIfCountChanged(d, "c", commentCount(d));
      }
      socket.send(["set_comment_read", o.id]);
    }
  },
  set_message_read: function(o, _, tab) {
    var d = pageData[tab.nUri], time = new Date(o.time);
    if (!d || time > (d.lastMessageRead[o.threadId] || 0)) {
      if (d) {
        d.lastMessageRead[o.threadId] = time;
        tellTabsIfCountChanged(d, "m", messageCount(d));
      }
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
      api.tabs.emit(tab, "threads", {threads: d.threads, read: d.lastMessageRead});
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
  open_deep_link: function(data, _, tab) {
    var uriData = pageData[data.nUri];
    if (uriData) {
      var tab = tab.nUri == data.nUri ? tab : uriData.tabs[0];
      if (tab.ready) {
        api.tabs.emit(tab, "open_slider_to", {
          force: true,
          trigger: "deepLink",
          locator: data.locator});
      } else {
        createDeepLinkListener(data, tab.id);
      }
      api.tabs.select(tab.id);
    } else {
      api.tabs.open(data.nUri, function (tabId) {
        createDeepLinkListener(data, tabId);
      });
      return true;
    }
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
      // uncomment second clause below to develop /r/ page using production deep links
      var hasForwarded = tab.url.indexOf(getServer() + "/r/") < 0 /* && tab.url.indexOf("dev.ezkeep.com") < 0 */;
      if (hasForwarded) {
        api.log("[createDeepLinkListener] Sending deep link to tab " + tab.id, link.locator);
        api.tabs.emit(tab, "open_slider_to", {trigger: "deepLink", locator: link.locator});
        api.tabs.on.ready.remove(deepLinkListener);
      }
    }
  });
}

function initTab(tab, o) {  // o is pageData[tab.nUri]
  o.counts = {
    n: -notifications.filter(function(n) {return new Date(n.time) > notificationsRead.time}).length,
    c: commentCount(o),
    m: messageCount(o)};
  var data = {
    kept: !!o.kept,
    private: o.kept == "private",
    keepers: o.keepers,
    keeps: o.keeps,
    otherKeeps: o.keeps - o.keepers.length - (o.kept == "public" ? 1 : 0),
    sensitive: !!o.sensitive,
    neverOnSite: !!o.neverOnSite,
    counts: o.counts};

  if (rules.message && o.counts.m < 0) {  // unread message(s)
    var ids = unreadThreadIds(o.threads, o.lastMessageRead);
    data.trigger = "message";
    data.locator = "/messages" + (ids.length > 1 ? "" : "/" + ids[0]);
    ids.forEach(function(id) {
      socket.send(["get_thread", id]);
    });
  } else if (rules.comment && o.counts.c < 0 && !o.neverOnSite) {  // unread comment(s)
    data.trigger = "comment";
    data.locator = "/comments";
  } else if (!o.kept && !o.neverOnSite && (!o.sensitive || !rules.sensitive)) {
    var url = tab.url;
    if (rules.url && urlPatterns.some(function(re) {return re.test(url)})) {
      api.log("[initTab]", tab.id, "restricted");
    } else if (rules.shown && o.shown) {
      api.log("[initTab]", tab.id, "shown before");
    } else {
      if (api.prefs.get("showSlider")) {
        data.rules = {scroll: rules.scroll}; // only the relevant one(s)
      }
      tab.autoShowSec = (rules.focus || 0)[0];
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

function commentCount(d) {  // comments only count as unread if by a friend. negative means unread.
  return -d.comments.filter(function(c) {
      return friendsById[c.user.id] && new Date(c.createdAt) > d.lastCommoentRead;
    }).length || d.comments.length;
}

function messageCount(d) {
  var n = 0, nUnr = 0;
  for (var i = 0; i < d.threads.length; i++) {
    var th = d.threads[i], thReadTime = new Date(d.lastMessageRead[th.id] || 0);
    n += th.messageCount;
    for (var id in th.messageTimes) {
      if (new Date(th.messageTimes[id]) > thReadTime) {
        nUnr++;
      }
    }
  }
  return -nUnr || n;
}

function unreadThreadIds(threads, readTimes) {
  return threads.filter(function(th) {
    var readTime = new Date(readTimes[th.id] || 0);
    for (var id in th.messageTimes) {
      if (new Date(th.messageTimes[id]) > readTime) {
        return true;
      }
    }
  }).map(function(t) {return t.id});
}

function countUnreadNotifications() {
  for (var n = 0; n < notifications.length; n++) {
    if (new Date(notifications[n].time) <= notificationsRead.time) break;
  }
  notificationsRead.unread = n;
  api.tabs.eachSelected(function(tab) {
    var d = pageData[tab.nUri];
    if (d && d.counts && d.counts.n != -n) {
      d.counts.n = -n;
      api.tabs.emit(tab, "counts", d.counts);
    }
  });
}

function tellTabsIfCountChanged(d, key, count) {
  if (d.counts[key] != count) {
    d.counts[key] = count;
    d.tabs.forEach(function(tab) {
      api.tabs.emit(tab, "counts", d.counts);
    });
  }
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

  ajax("POST", "/bookmarks/remove", {url: url}, function(o) {
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

  ajax("POST", "/bookmarks/private", {url: url, private: priv}, function(o) {
    api.log("[setPrivate] response:", o);
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
      resp.server = getServer();
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
    ajax("POST", "/bookmarks/add", {
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
    api.log("[tabs.on.ready] emitting: %i %o", tab.id, emission[0]);
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

function compilePatterns(arr) {
  for (var i = 0; i < arr.length; i++) {
    arr[i] = new RegExp(arr[i], "");
  }
  return arr;
}

function hasId(id) {
  return function(o) {return o.id == id};
}

function getFullyQualifiedKey(key) {
  return (api.prefs.get("env") || "production") + "_" + key;
}

function getServer() {
  return api.prefs.get("env") === "development" ? "dev.ezkeep.com:9000" : "keepitfindit.com";
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
      version: api.version,
      agent: api.browserVersion},
    function done(data) {
      api.log("[startSession] reason: %s session: %o", api.loadReason, data);
      logEvent("extension", "authenticated");

      session = data;
      socket = api.socket.open((dev ? "ws://" : "wss://") + getServer() + "/ext/ws", socketHandlers);
      socket.send(["get_notifications", 10]);
      socket.send(["get_last_notify_read_time"]);
      socket.send(["get_friends"]);
      logEvent.catchUp();

      rules = data.rules;
      urlPatterns = compilePatterns(data.patterns);
      store("kifi_installation_id", data.installationId);
      delete session.rules;
      delete session.patterns;
      delete session.installationId;

      // Locate or create KeepIt bookmark folder.
      getBookmarkFolderInfo(getStored("bookmark_id"), function(info) {
        store("bookmark_id", info.keepItId);
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
    var server = getServer();
    api.popup.open({
      name: "kifi-authenticate",
      url: "http://" + server + "/authenticate/facebook",
      width: 1020,
      height: 530}, {
      navigate: function(url) {
        if (url == "http://" + server + "/#_=_") {
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
    url: "http://" + getServer() + "/session/end",
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
