var api = api || require("./api");

// ===== Async

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

var eventFamilies = {slider:1, search:1, extension:1, account:1, notification:1};

function logEvent(eventFamily, eventName, metaData, prevEvents) {
  if (!eventFamilies[eventFamily]) {
    api.log.error("[logEvent] invalid event family:", eventFamily);
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
  api.log("[logEvent]", ev);
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

var socketHandlers = {
  message: function(data) {
    api.log("[socket:message]", data);
    var activeTab = api.tabs.getActive();
    if (activeTab) {
      api.tabs.emit(activeTab, "show_notification", data);
    }
  },
  comment: function(data) {
    api.log("[socket:comment]", data);
    var activeTab = api.tabs.getActive();
    if (activeTab) {
      api.tabs.emit(activeTab, "show_notification", data);
    }
  },
  notify: function(data) {
    api.log("[socket:notify]", data);
    var activeTab = api.tabs.getActive();
    if (activeTab) {
      api.tabs.emit(activeTab, "show_notification", data);
    }
  },
  event: function(data) {
    api.log("[socket:event]", data);
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
      api.log("[init_slider_please] emitting:", tab.id, emission);
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

    function getSliderInfo(tab, respond) {
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
  post_comment: function(data, respond) {
    postComment(data, respond);
    return true;
  },
  delete_comment: function(id, respond) {
    ajax("POST", "http://" + getConfigs().server + "/comments/" + id + "/remove", function(o) {
      api.log("[deleteComment] response:", o);
      respond(o);
    });
    return true;
  },
  normalize: function(_, respond, tab) {
    socket.send(["normalize", tab.url], respond);
    return true;
  },
  comments: function(_, respond, tab) {
    socket.send(["get_comments", tab.url], respond);
    return true;
  },
  threads: function(_, respond, tab) {
    socket.send(["get_message_threads", tab.url], respond);
    return true;
  },
  thread: function(id, respond) {
    socket.send(["get_message_thread", id], respond);
    return true;
  },
  session: function(_, respond) {
    respond(session);
  },
  get_friends: function(_, respond) {
    ajax("GET", "http://" + getConfigs().server + "/users/friends", respond);
    return true;
  },
  add_deep_link_listener: function(data, respond, tab) {
    createDeepLinkListener(data, tab.id, respond);
    return true;
  }
});

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

function checkKeepStatus(tab, callback) {
  if (tab.keepStatusKnown) return;  // already in progress or done

  if (!session) {
    api.log("[checkKeepStatus] no session");
    return;
  }

  api.log("[checkKeepStatus]", tab);

  tab.keepStatusKnown = true;  // setting before request to avoid making two overlapping requests
  ajax("GET", "http://" + getConfigs().server + "/bookmarks/check", {uri: tab.url, ver: session.rules.version}, function done(o) {
    setIcon(tab, o.kept);
    if (o.rules) {
      session.rules = o.rules;
      session.patterns = o.patterns;
      compilePatterns(session);
    }
    callback && callback(o);
  }, function fail(xhr) {
    api.log("[checkKeepStatus] error:", xhr.responseText);
    delete tab.keepStatusKnown;
  });
}

function setIcon(tab, kept) {
  api.log("[setIcon] tab:", tab.id, "kept:", kept);
  api.icon.set(tab, kept == null ? "icons/keep.faint.png" : kept ? "icons/kept.png" : "icons/keep.png");
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

api.tabs.on.focus.add(function(tab) {
  api.log("[tabs.on.focus]", tab);
  if (tab.autoShowSec != null && !tab.autoShowTimer) {
    scheduleAutoShow(tab);
  } else {
    checkKeepStatus(tab);
  }
});

api.tabs.on.blur.add(function(tab) {
  api.log("[tabs.on.blur]", tab);
  api.timers.clearTimeout(tab.autoShowTimer);
  delete tab.autoShowTimer;
});

api.tabs.on.loading.add(function(tab) {
  api.log("[tabs.on.loading]", tab);
  setIcon(tab);

  checkKeepStatus(tab, function gotKeptStatus(resp) {
    var data = {metro: session.experiments.indexOf("metro") >= 0};
    ["kept", "private", "keepers", "keeps", "sensitive", "neverOnSite",
     "numComments", "unreadComments", "numMessages", "unreadMessages"].forEach(function(key) {
      data[key] = resp[key];
    });
    data.otherKeeps = (data.keeps || 0) - (data.keepers || []).length - (data.kept && !data.private ? 1 : 0);
    if (session.rules.rules.message && /^\/messages/.test(resp.locator) ||
        session.rules.rules.comment && /^\/comments/.test(resp.locator) && !resp.neverOnSite) {
      api.log("[gotKeptStatus]", tab.id, resp.locator);
      data.trigger = resp.locator.substr(1, 7); // "message" or "comment"
      data.locator = resp.locator;
    } else if (!resp.kept && !resp.neverOnSite && (!resp.sensitive || !session.rules.rules.sensitive)) {
      var url = tab.url;
      if (session.rules.rules.url && session.patterns.some(function(re) {return re.test(url)})) {
        api.log("[gotKeptStatus]", tab.id, "restricted");
      } else if (session.rules.rules.shown && resp.shown) {
        api.log("[gotKeptStatus]", tab.id, "shown before");
      } else {
        if (api.prefs.get("showSlider")) {
          data.rules = { // only the relevant ones
            scroll: session.rules.rules.scroll,
            viewport: session.rules.rules.viewport};
        }
        tab.autoShowSec = (session.rules.rules[resp.keepers ? "friendKept" : "focus"] || [])[0];
        if (tab.autoShowSec != null && api.tabs.isFocused(tab)) {
          scheduleAutoShow(tab);
        }
      }
    }

    api.log("[gotKeptStatus]", tab.id, data);
    if (tab.ready) {
      api.tabs.emit(tab, "init_slider", data);
    } else {
      tab.emitOnReady = ["init_slider", data];
    }
  });
});

api.tabs.on.ready.add(function(tab) {
  api.log("[tabs.on.ready]", tab);
  logEvent("extension", "pageLoad");

  var emission = tab.emitOnReady;  // TODO: promote emitOnReady to API layer
  if (emission) {
    api.log("[tabs.on.ready] emitting:", tab.id, emission);
    api.tabs.emit(tab, emission[0], emission[1]);
    delete tab.emitOnReady;
  }
});

api.tabs.on.complete.add(function(tab) {
  api.log("[tabs.on.complete]", tab);
});

api.tabs.on.unload.add(function(tab) {
  api.log("[tabs.on.unload]", tab);
  api.timers.clearTimeout(tab.autoShowTimer);
  delete tab.autoShowTimer;
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
  removeFromConfigs("user"); // remove this line in early Feb or so
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
      api.log("[startSession] done, loadReason:", api.loadReason, "session:", data);
      logEvent("extension", "authenticated");

      session = compilePatterns(data);
      socket = api.socket.open(
        (api.prefs.get("env") === "development" ? "ws://" : "wss://") + getConfigs().server + "/ext/ws",
        socketHandlers);
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
  api.tabs.each(function(tab) {
    if (!tab.keepStatusKnown) {
      setIcon(tab);
      if (api.tabs.isSelected(tab)) {
        checkKeepStatus(tab);
      }
    }
  });
});
