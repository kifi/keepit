var api = api || require("./api");
var meta = meta || require("./meta");

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
        a.push(encodeURIComponent(key) + "=" + encodeURIComponent(val == null ? "" : val));
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
    if (history.length > HISTORY_SIZE) {
      history.pop();
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
    //var config = getConfigs();
    // var data = {
    //   "version": 1,
    //   "time": new Date - t0,
    //   "installId": config.kifi_installation_id, /* User's ExternalId[KifiInstallation] */
    //   "events": _eventLog};
    // api.log("[EventLog] sending:", data);
    // ajax("POST", "http://" + config.server + "/users/events", data, function done(o) {
    //   api.log("[EventLog] done:", o)
    // }, function fail(xhr) {
    //   api.log("[EventLog] fail:", xhr.responseText);
    // });

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
    ajax(data ? "POST" : "DELETE", "http://" + getConfigs().server + "/comments/follow", {url: tab.url}, function(o) {
      api.log("[follow] resp:", o);
    });
  },
  get_conf: function(data, respond) {
    respond({config: getConfigs(), session: session});
  },
  set_conf: function(data) {
    setConfigs(data.key, data.value);
  },
  remove_conf: function(data) {
    setConfigs(data.key);
  },
  set_page_icon: function(data, respond, tab) {
    setPageIcon(tab.id, data);
  },
  require: function(data, respond, tab) {
    injectDeps(tab.id, data, respond);
    return true;
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
    logEvent.apply(null, data.args);
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
    createDeepLinkListener(data.link, tab.id, respond);
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
  chrome.tabs.onUpdated.addListener(function deepLinkListener(tabId, changeInfo, tab) {
    var now = new Date();
    if (now - createdTime > 15000) {
      chrome.tabs.onUpdated.removeListener(deepLinkListener);
      api.log("[createDeepLinkListener] Listener timed out.");
      return;
    }
    if (linkTabId == tabId && changeInfo.status == "complete") {
      var hasForwarded = tab.url.indexOf(getConfigs().server + "/r/") == -1 && tab.url.indexOf("dev.ezkeep.com") == -1;
      if (hasForwarded) {
        api.log("[createDeepLinkListener] Sending deep link to tab " + tabId, link.locator);
        api.tabs.emit(tabId, "deep_link", link.locator);
        chrome.tabs.onUpdated.removeListener(deepLinkListener);
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
    chrome.bookmarks.get(keepItBookmarkId, function(bm) {
      if (bm && bm.length) {
        // We created this bookmark folder. We continue to use it even if user has moved it or renamed it.
        ensurePublicPrivate(bm[0]);
      } else {
        findOrCreateKeepIt();
      }
    });
  } else {
    findOrCreateKeepIt();
  }

  function findOrCreateKeepIt() {
    chrome.bookmarks.getChildren("0", function(bm) {
      var parent = bm.filter(function(bm) { return bm.title == "Bookmarks Bar" })[0] || bm[0];
      chrome.bookmarks.getChildren(parent.id, function(bm) {
        var keepIt = bm.filter(function(bm) { return bm.title == "KeepIt" });
        if (keepIt.length) {
          ensurePublicPrivate(keepIt[0]);
        } else {
          chrome.bookmarks.create({parentId: parent.id, title: "KeepIt"}, function(bm) {
            ensurePublicPrivate(bm);
          });
        }
      });
    });
  }

  function ensurePublicPrivate(keepIt) {
    chrome.bookmarks.getChildren(keepIt.id, function(children) {
      var bm = children.filter(function(bm) { return bm.title == "public" });
      if (bm.length) {
        ensurePrivate({keepItId: keepIt.id, publicId: bm[0].id}, children);
      } else {
        chrome.bookmarks.create({parentId: keepIt.id, title: "public"}, function(bm) {
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
      chrome.bookmarks.create({parentId: info.keepItId, title: "private"}, function(bm) {
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
  var bookmark = {parentId: bmInfo[req.private ? "privateId" : "publicId"], title: req.title, url: req.url};
  chrome.bookmarks.create(bookmark, function(bm) {
    try {
      respond(bm);
      bookmark.isPrivate = !!req.private;
      postBookmarks(function(f) {f([bookmark])}, "HOVER_KEEP");
    } catch (e) {
       api.log.error(e);
    }
  });
}

function removeKeep(bmInfo, url, respond) {
  api.log("[removeKeep] url:", url);

  if (!session) {
    api.log("[removeKeep] no session");
    return;
  }

  chrome.bookmarks.search(url, function(bm) {
    bm.forEach(function(bm) {
      if (bm.url == url && (bm.parentId == bmInfo.publicId || bm.parentId == bmInfo.privateId)) {
        chrome.bookmarks.remove(bm.id);
      }
    });
  });

  ajax("POST", "http://" + getConfigs().server + "/bookmarks/remove", {url: req.url}, function(o) {
    api.log("[removeKeep] response:", o);
    respond(o);
  });
}

function setPrivate(bmInfo, url, priv, respond) {
  api.log("[setPrivate]", url, priv);

  var newParentId = priv ? bmInfo.privateId : bmInfo.publicId;
  var oldParentId = priv ? bmInfo.publicId : bmInfo.privateId;
  chrome.bookmarks.search(url, function(bm) {
    bm.forEach(function(bm) {
      if (bm.url == url && bm.parentId == oldParentId) {
        chrome.bookmarks.move(bm.id, {parentId: newParentId});
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
  var config = getConfigs();

  logEvent("search", "newSearch");

  if (!session) {
    api.log("[searchOnServer] no session");
    respond({"session": null, "searchResults": [], "userConfig": config});
    return;
  }

  if (request.query === '') {
    respond({"session": session, "searchResults": [], "userConfig": config});
    return;
  }

  ajax("GET", "http://" + config.server + "/search", {
      term: request.query,
      maxHits: config.max_res * 2,
      lastUUI: request.lastUUID,
      context: request.context,
      kifiVersion: api.version},
    function(results) {
      api.log("[searchOnServer] results:", results);
      respond({"session": session, "searchResults": results, "userConfig": config});
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

function onPageLoad(tab) {
  api.log("[onPageLoad] tab:", tab);
  logEvent("extension", "pageLoad");

  injectDeps(tab.id, {
    scripts: meta.contentScripts.reduce(function(a, s) {
        if (s[1].test(tab.url)) {
          a.push(s[0]);
        }
        return a;
      }, [])},
    api.noop);

  checkWhetherKept(tab.url, function(isKept) {
    setPageIcon(tab.id, isKept);

    if (restrictedUrlPatternsForHover.some(function(e) {return tab.url.indexOf(e) >= 0})) {
      api.log("[onPageLoad] restricted:", tab.url);
      return;
    } else if (userHistory.exists(tab.url)) {
      api.log("[onPageLoad] recently visited:", tab.url);
      return;
    }
    userHistory.add(tab.url);

    if (isKept) {
      api.log("[onPageLoad] already kept:", tab.url);
    } else {
      var sliderDelaySec = getConfigs().hover_timeout;
      if (sliderDelaySec > 0) {
        api.tabs.emit(tab.id, "auto_show_after", sliderDelaySec * 1000);
      }
    }
  });
}

// Kifi icon in location bar
api.icon.on.click.push(function(tab) {
  api.tabs.emit(tab.id, "button_click");
});

function checkWhetherKept(url, callback) {
  api.log("[checkWhetherKept] url:", url);
  if (!session) {
    api.log("[checkWhetherKept] no session");
    return;
  }

  ajax("GET", "http://" + getConfigs().server + "/bookmarks/check", {uri: url}, function done(o) {
    callback(o.user_has_bookmark);
  }, function fail(xhr) {
    api.log("[checkWhetherKept] error:", xhr.responseText);
    callback(false);
  });
}

function setPageIcon(tabId, kept) {
  api.log("[setPageIcon] tab:", tabId, "kept:", !!kept);
  chrome.pageAction.setIcon({tabId: tabId, path: kept ? "icons/kept.png" : "icons/keep.png"});
  chrome.pageAction.show(tabId);
}

function injectDeps(tabId, details, callback) {
  var injected = details.injected || {};
  function notYetInjected(path) {
    return !injected[path];
  }

  details.scripts = details.scripts.reduce(function(a, s) {
    return a.concat(transitiveClosure(s));
  }, []).filter(isUnique).filter(notYetInjected);

  details.styles = details.scripts.reduce(function(a, s) {
    a.push.apply(a, meta.styleDeps[s]);
    return a;
  }, []).filter(isUnique).filter(notYetInjected);

  details.script = "injected=" + JSON.stringify(
    details.styles.reduce(addEntry,
      details.scripts.reduce(addEntry, injected)));

  api.log("[injectDeps] details: " + JSON.stringify(details));

  api.tabs.inject(tabId, details, callback);

  function addEntry(o, k) {
    o[k] = true;
    return o;
  }
}

function transitiveClosure(path) {
  var deps = meta.scriptDeps[path];
  if (deps) {
    deps = deps.reduce(function(a, s) {
      return a.concat(transitiveClosure(s));
    }, []);
    deps.push(path);
    return deps.filter(isUnique);
  } else {
    return [path];
  }
}

function isUnique(value, index, array) {
  return array.indexOf(value) == index;
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

// Tab may be older than current kifi installation and so not yet have icon and any content script(s).
api.tabs.on.activate.push(sprinkleSomeKiFiOn);

api.tabs.on.navigate.push(function(tab) {
  setPageIcon(tab.id, false);
});

api.tabs.on.load.push(onPageLoad);

function sprinkleSomeKiFiOn(tab) {
  if (/^https?:/.test(tab.url)) {
    api.tabs.inject(tab.id, {script: "window.injected"}, function(val) {
      if (!val) {
        api.log("[sprinkleSomeKiFiOn] old tab:", tab.id);
        setPageIcon(tab.id, false);
        onPageLoad(tab);
      }
    });
  }
}

function getFullyQualifiedKey(key) {
  return (api.storage.env || "production") + "_" + key;
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
  try {
    var env = api.storage.env;
    if (!env) {
      api.storage.env = env = "production";
    }

    return {
      "env": env,
      "server": env == "development" ? "dev.ezkeep.com:9000" : "keepitfindit.com",
      "kifi_installation_id": api.storage[getFullyQualifiedKey("kifi_installation_id")],
      "bookmark_id": api.storage[getFullyQualifiedKey("bookmark_id")],
      "hover_timeout": parseNonNegIntOr(api.storage[getFullyQualifiedKey("hover_timeout")], 10),
      "show_score": parseBoolOr(api.storage[getFullyQualifiedKey("show_score")], false),
      "max_res": parseNonNegIntOr(api.storage[getFullyQualifiedKey("max_res")], 5)};
  } catch (e) {
    api.log.error(e);
  }
}

function parseNonNegIntOr(val, defaultValue) {
  var n = parseInt(val, 10);
  return isNaN(n) ? defaultValue : Math.abs(n);
}

function parseBoolOr(val, defaultValue) {
  return val === "yes" || val === true || val === "true" || defaultValue;
}

api.on.install.push(function() {
  logEvent("extension", "install");
});
api.on.update.push(function() {
  logEvent("extension", "update");
  removeFromConfigs("user"); // remove this line in early Feb or so
});

// ===== Session management

var session;

function authenticate(callback) {
  var config = getConfigs();
  if (config.env == "development") {
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

      if (api.loadReason == "install" || config.env == "development") {
        postBookmarks(chrome.bookmarks.getTree, "INIT_LOAD");
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
  chrome.windows.create({type: "popup", url: "http://" + getConfigs().server + "/session/end", width: 200, height: 100}, function(win) {
    api.log("[deauthenticate] created popup:", win);
    callback();
  });
}

// ===== Main (executed upon install, reinstall, update, reenable, and browser start)

logEvent("extension", "started");

meta.contentScripts.forEach(function(arr) {
  var path = arr[0];
  api.scripts.register(arr[1], transitiveClosure(path), meta.styleDeps[path]);
});

authenticate(function() {
  api.log("[main] authenticated");

  // chrome.tabs.query({windowType: "normal", active: true, status: "complete"}, function(tabs) {
  //   tabs.forEach(sprinkleSomeKiFiOn);
  // });
});
