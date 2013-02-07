var api = api || require("./api");

// ===== Async requests

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

// ===== User history

var userHistory = new UserHistory();

function UserHistory() {
  var HISTORY_SIZE = 200;
  this.history = [];
  this.add = function(uri) {
    api.log("[UserHistory.add]", uri);
    this.history.unshift(uri);
    if (this.history.length > HISTORY_SIZE) {
      this.history.pop();
    }
  }
  this.exists = function(uri) {
    for (var i = 0; i < this.history.length; i++) {
      if (this.history[i] === uri) {
        return true;
      }
    }
    return false;
  }
}

// ===== Event logging

var _eventLog = [];
var eventFamilies = {slider:1, search:1, extension:1, account:1, notification:1};

function logEvent(eventFamily, eventName, metaData, prevEvents) {
  if (!eventFamilies[eventFamily]) {
    api.log("[logEvent] invalid event family:", eventFamily);
    return;
  }
  var event = {
      "time": new Date().getTime(),
      "eventFamily": eventFamily, /* Category (see eventFamilies) */
      "eventName": eventName}; /* Any key for this event */
  if (metaData) {
    event.metaData = metaData; /* Any js object that you would like to attach to this event. i.e., number of total results shown, which result was clicked, etc. */
  }
  if (prevEvents && prevEvents.length) {
    event.prevEvents = prevEvents; /* a list of previous ExternalId[Event]s that are associated with this action. !!!: The frontend determines what is associated with what. */
  }
  _eventLog.push(event);
}

var eventLogDelay = 4000;
api.timers.setTimeout(function maybeSend() {
  if (_eventLog.length) {
    var t0 = _eventLog[0].time;
    _eventLog.forEach(function(e) { e.time -= t0 }); // relative times = fewer bytes
    var config = getConfigs();
    var data = {
      "version": 1,
      "time": new Date - t0,
      "installId": config.kifi_installation_id, /* User's ExternalId[KifiInstallation] */
      "events": _eventLog};
    api.log("[EventLog] sending:", data);
    ajax("POST", "http://" + config.server + "/users/events", data, function done(o) {
      api.log("[EventLog] done:", o)
    }, function fail(xhr) {
      api.log("[EventLog] fail:", xhr.responseText);
    });

    _eventLog.length = 0;
    eventLogDelay = Math.round(Math.max(Math.sqrt(eventLogDelay), 5 * 1000));
  } else {
    eventLogDelay = Math.min(eventLogDelay * 2, 60 * 1000);
  }
  api.timers.setTimeout(maybeSend, eventLogDelay);
}, 4000);

// ===== Handling messages from content scripts or other extension pages

api.port.on({
  log_in: function(data, respond) {
    authenticate(function() {
      respond(session);
    });
    return true;
  },
  log_out: function(data, respond) {
    deauthenticate(respond);
    return true;
  },
  get_keeps: searchOnServer,
  add_bookmarks: function(data, respond) {
    getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
      addKeep(info, data, respond);
    });
    return true;
  },
  unkeep: function(data, respond, tab) {
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
  get_prefs: function(data, respond) {
    respond({session: session, prefs: api.prefs.get("env", "showSlider", "maxResults", "showScores")});
  },
  set_prefs: function(data) {
    api.prefs.set(data);
  },
  set_page_icon: function(data, respond, tab) {
    tab.kept = data;
    setIcon(tab);
  },
  get_slider_info: function(data, respond, tab) {
    if (session) {
      getSliderInfo(tab, respond);
    } else {
      authenticate(getSliderInfo.bind(null, tab, respond));
    }
    return true;
  },
  get_slider_updates: function(data, respond, tab) {
    ajax("GET", "http://" + getConfigs().server + "/users/slider/updates", {url: tab.url}, respond);
    return true;
  },
  log_event: function(data) {
    logEvent.apply(null, data);
  },
  get_comments: function(data, respond, tab) {
    ajax("GET", "http://" + getConfigs().server +
      (data.kind == "public" ? "/comments/public" : "/messages/threads") +
      (data.commentId ? "/" + data.commentId : "?url=" + encodeURIComponent(tab.url)),
      respond);
    return true;
  },
  post_comment: function(data, respond) {
    postComment(data, respond);
    return true;
  },
  get_friends: function(data, respond) {
    ajax("GET", "http://" + getConfigs().server + "/users/friends", respond);
    return true;
  },
  add_deep_link_listener: function(data, respond, tab) {
    createDeepLinkListener(data, tab.id, respond);
    return true;
  }});

function getSliderInfo(tab, respond) {
  ajax("GET", "http://" + getConfigs().server + "/users/slider", {url: tab.url}, function(o) {
    o.session = session;
    respond(o);
  });
}

function createDeepLinkListener(link, linkTabId, respond) {
  var createdTime = new Date();
  api.tabs.on.ready.add(function deepLinkListener(tab) {
    var now = new Date();
    if (now - createdTime > 15000) {
      api.tabs.on.ready.remove(deepLinkListener);
      api.log("[createDeepLinkListener] Listener timed out.");
      return;
    }
    if (linkTabId == tab.id) {
      var hasForwarded = tab.url.indexOf(getConfigs().server + "/r/") < 0 && tab.url.indexOf("dev.ezkeep.com") < 0;
      if (hasForwarded) {
        api.log("[createDeepLinkListener] Sending deep link to tab " + tab.id, link.locator);
        api.tabs.emit(tab, "deep_link", link.locator);
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
      respond(o);
    });
}

function searchOnServer(request, respond) {
  logEvent("search", "newSearch", {"query": request.query});

  if (!session) {
    api.log("[searchOnServer] no session");
    respond({"session": null, "searchResults": []});
    return;
  }

  if (request.query === '') {
    respond({"session": session, "searchResults": [], "maxResults": 0});
    return;
  }

  var config = getConfigs(), maxResults = api.prefs.get("maxResults");
  ajax("GET", "http://" + config.server + "/search", {
      term: request.query,
      maxHits: maxResults * 2,
      lastUUI: request.lastUUID,
      context: request.context,
      kifiVersion: api.version},
    function(results) {
      api.log("[searchOnServer] results:", results);
      respond({
        "session": session,
        "searchResults": results,
        "maxResults": maxResults,
        "showScores": api.prefs.get("showScores"),
        "server": config.server});
    });
  return true;
}

var restrictedUrlPatternsForHover = [
  "www.facebook.com",
  "keepitfindit.com",
  "ezkeep.com",
  "localhost:",
  "maps.google.com",
  "google.com*tbm=isch",
  "www.google.com",
  "google.com"];

// Kifi icon in location bar
api.icon.on.click.add(function(tab) {
  api.tabs.emit(tab, "button_click");
});

function checkKeepStatus(tab, callback) {
  if (tab.hasOwnProperty("kept")) return;  // already in progress or done

  if (!session) {
    api.log("[checkKeepStatus] no session");
    return;
  }

  api.log("[checkKeepStatus]", tab);
  tab.kept = undefined;

  ajax("GET", "http://" + getConfigs().server + "/bookmarks/check", {uri: tab.url}, function done(o) {
    tab.kept = o.user_has_bookmark;
    setIcon(tab);
    callback && callback();
  }, function fail(xhr) {
    api.log("[checkKeepStatus] error:", xhr.responseText);
    delete tab.kept;
    callback && callback();
  });
}

function setIcon(tab) {
  api.log("[setIcon] tab:", tab.id, "kept:", tab.kept);
  api.icon.set(tab, tab.kept == null ? "icons/keep.faint.png" : tab.kept ? "icons/kept.png" : "icons/keep.png");
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
  if (tab.autoShowEligible && !tab.autoShowTimer) {
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

  checkKeepStatus(tab, function() {
    if (tab.kept === false) {  // false, not undefined
      var url = tab.url;
      if (restrictedUrlPatternsForHover.some(function(e) {return url.indexOf(e) >= 0})) {
        api.log("[tabs.on.loading:2] restricted:", url);
        return;
      }

      if (userHistory.exists(url)) {
        api.log("[tabs.on.loading:2] recently visited:", url);
      } else {
        userHistory.add(url);
        tab.autoShowEligible = true;
        if (api.tabs.isFocused(tab)) {
          scheduleAutoShow(tab);
        }
      }
    }
  });
});

api.tabs.on.ready.add(function(tab) {
  api.log("[tabs.on.ready]", tab);
  logEvent("extension", "pageLoad");

  if (tab.autoShowOnReady) {
    api.log("[tabs.on.ready] auto showing:", tab);
    api.tabs.emit(tab, "auto_show");
    delete tab.autoShowOnReady;
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
  if (api.prefs.get("showSlider") !== false) {
    tab.autoShowTimer = api.timers.setTimeout(function() {
      delete tab.autoShowEligible;
      delete tab.autoShowTimer;
      if (api.prefs.get("showSlider") !== false) {
        if (tab.ready) {
          api.log("[scheduleAutoShow:1] fired for tab:", tab.id);
          api.tabs.emit(tab, "auto_show");
        } else {
          api.log("[scheduleAutoShow:1] fired but tab not ready:", tab.id);
          tab.autoShowOnReady = true;
        }
      }
    }, 30000);
  }
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

var session;

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

      session = data;
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
    setIcon(tab);
    if (api.tabs.isSelected(tab)) {
      checkKeepStatus(tab);
    }
  });
});

// TODO: remove the temporary prefs storage migration code below after Jan 31.
if (this.chrome) {
  ["max_res","show_score"].forEach(function(name) {
    var val = api.storage["production_" + name] || api.storage["development_" + name];
    if (val) api.prefs.set({max_res: "maxResults", show_score: "showScores"}[name], val === "yes" ? true : val);
    delete api.storage["production_" + name];
    delete api.storage["development_" + name];
  });
}
if (api.storage.env) {
  if (api.storage.env === "development") {
    api.prefs.set("env", "development");
  }
  delete api.storage.env;
}

// TODO: remove the temporary prefs storage migration code below after Feb 10.
if (this.chrome) {
  delete api.storage[":sliderDelay"];
}
