function noop() {}

// ===== Logging

function log() {
  console.log.apply(console, Array.prototype.concat.apply(["[" + new Date().getTime() + "] "], arguments));
}

function error(exception, message) {
  console.error(exception);
  console.error((message ? "[" + message + "] " : "") + exception.message);
  console.error(exception.stack);
  //alert("exception: " + exception.message);
}

// ===== Ajax

function ajax(method, uri, data, done, fail) {  // method and uri are required
  if (typeof data == "function") {  // shift args if data is missing and done is present
    fail = done, done = data, data = null;
  }

  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function() {
    if (this.readyState == 4) {
      var arg = /^application\/json/.test(this.getResponseHeader("Content-Type")) ? JSON.parse(this.responseText) : this;
      ((this.status == 200 ? done : fail) || noop)(arg);
      done = fail = noop;  // ensure we don't call a callback again
    }
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
  xhr.open(method, uri, true);
  if (data) {
    data = JSON.stringify(data);
    xhr.setRequestHeader("Content-Type", "application/json; charset=utf-8");
  }
  xhr.send(data);
}

// ===== User history

var userHistory = new UserHistory();

function UserHistory() {
  var HISTORY_SIZE = 200;
  this.history = [];
  this.add = function(uri) {
    log("[UserHistory.add]", uri);
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
    log("[logEvent] invalid event family:", eventFamily);
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
setTimeout(function maybeSend() {
  if (_eventLog.length) {
    var t0 = _eventLog[0].time;
    _eventLog.forEach(function(e) { e.time -= t0 }); // relative times = fewer bytes
    var config = getConfigs();
    var data = {
      "version": 1,
      "time": new Date - t0,
      "installId": config.kifi_installation_id, /* User's ExternalId[KifiInstallation] */
      "events": _eventLog};
    log("[EventLog] sending:", data);
    ajax("POST", "http://" + config.server + "/users/events", data, function done(o) {
      log("[EventLog] done:", o)
    }, function fail(xhr) {
      log("[EventLog] fail:", xhr.responseText);
    });

    _eventLog.length = 0;
    eventLogDelay = Math.round(Math.max(Math.sqrt(eventLogDelay), 5 * 1000));
  } else {
    eventLogDelay = Math.min(eventLogDelay * 2, 60 * 1000);
  }
  setTimeout(maybeSend, eventLogDelay);
}, 4000);

// ===== Message handling

// Listen for the content script to send a message to the background page.
chrome.extension.onMessage.addListener(function(request, sender, sendResponse) {
  var tab = sender.tab;
  log("[onMessage] handling:", request, "for", tab && tab.id);
  try {
    switch (request && request.type) {
      case "log_in":
        authenticate(function() {
          sendResponse(session);
        });
        return true;
      case "log_out":
        deauthenticate(sendResponse);
        return true;
      case "get_keeps":
        return searchOnServer(request, sendResponse, tab);
      case "add_bookmarks":
        getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
          addKeep(info, request, sendResponse, tab);
        });
        return true;
      case "unkeep":
        getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
          removeKeep(info, request, sendResponse, tab);
        });
        return true;
      case "set_private":
        getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
          setPrivate(info, request, sendResponse, tab);
        });
        return true;
      case "follow":
        ajax(request.follow ? "POST" : "DELETE", "http://" + getConfigs().server + "/comments/follow", {url: tab.url}, function(o) {
          log("[follow] resp:", o);
        });
        return;
      case "get_conf":
        sendResponse({config: getConfigs(), session: session});
        return;
      case "set_conf":
        setConfigs(request.key, request.value);
        sendResponse();
        return;
      case "remove_conf":
        setConfigs(request.key);
        sendResponse();
        return;
      case "set_page_icon":
        setPageIcon(tab, request.is_kept);
        return;
      case "require":
        require(tab.id, request, sendResponse);
        return true;
      case "get_slider_info":
        ajax("GET", "http://" + getConfigs().server + "/users/slider", {url: tab.url}, function(o) {
          o.session = session;
          sendResponse(o);
        });
        return true;
      case "get_slider_updates":
        ajax("GET", "http://" + getConfigs().server + "/users/slider/updates", {url: tab.url}, sendResponse);
        return true;
      case "log_event":
        logEvent.apply(null, request.args);
        return;
      case "get_comments":
        ajax("GET", "http://" + getConfigs().server +
          (request.kind == "public" ? "/comments/public" : "/messages/threads") +
          (request.commentId ? "/" + request.commentId : "?url=" + encodeURIComponent(tab.url)),
          sendResponse);
        return true;
      case "post_comment":
        postComment(request, sendResponse);
        return true;
      case "get_friends":
        ajax("GET", "http://" + getConfigs().server + "/users/friends", sendResponse);
        return true;
      case "add_deep_link_listener":
        createDeepLinkListener(request.link, tab.id, sendResponse);
        return true;
      default:
        log("Ignoring unknown message:", request);
    }
  } catch (e) {
    error(e);
  } finally {
    log("[onMessage] done:", request);
  }
});

function createDeepLinkListener(link, linkTabId, sendResponse) {
  var createdTime = new Date();
  chrome.tabs.onUpdated.addListener(function deepLinkListener(tabId, changeInfo, tab) {
    var now = new Date();
    if(now - createdTime > 15000) {
      chrome.tabs.onUpdated.removeListener(deepLinkListener);
      log("[createDeepLinkListener] Listener timed out.");
      return;
    }
    if(linkTabId == tabId && changeInfo.status == "complete") {
      var hasForwarded = tab.url.indexOf(getConfigs().server + "/r/") == -1 && tab.url.indexOf("dev.ezkeep.com") == -1;
      if(hasForwarded) {
        log("[createDeepLinkListener] Sending deep link to tab " + tabId, link.locator);
        chrome.tabs.sendMessage(tabId, {type: "deep_link", link: link.locator});
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
  log("[getBookmarkFolderInfo]");

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
    log("[getBookmarkFolderInfo] done");
    callback(info);
  }
}

function addKeep(bmInfo, req, sendResponse, tab) {
  log("[addKeep] private: " + !!req.private + ", title: " + req.title, tab);
  var bookmark = {parentId: bmInfo[req.private ? "privateId" : "publicId"], title: req.title, url: req.url};
  chrome.bookmarks.create(bookmark, function(bm) {
    try {
      sendResponse(bm);
      bookmark.isPrivate = !!req.private;
      postBookmarks(function(f) {f([bookmark])}, "HOVER_KEEP");
    } catch (e) {
      error(e);
    }
  });
}

function removeKeep(bmInfo, req, sendResponse, tab) {
  log("[removeKeep] url:", req.url, tab);

  if (!session) {
    log("[removeKeep] no session");
    return;
  }

  chrome.bookmarks.search(req.url, function(bm) {
    bm.forEach(function(bm) {
      if (bm.url == req.url && (bm.parentId == bmInfo.publicId || bm.parentId == bmInfo.privateId)) {
        chrome.bookmarks.remove(bm.id);
      }
    });
  });

  ajax("POST", "http://" + getConfigs().server + "/bookmarks/remove", {url: req.url}, function(o) {
    log("[removeKeep] response:", o);
    sendResponse(o);
  });
}

function setPrivate(bmInfo, req, sendResponse, tab) {
  log("[setPrivate]", req.private, req.url, tab);

  var newParentId = req.private ? bmInfo.privateId : bmInfo.publicId;
  var oldParentId = req.private ? bmInfo.publicId : bmInfo.privateId;
  chrome.bookmarks.search(req.url, function(bm) {
    bm.forEach(function(bm) {
      if (bm.url == req.url && bm.parentId == oldParentId) {
        chrome.bookmarks.move(bm.id, {parentId: newParentId});
      }
    });
  });

  ajax("POST", "http://" + getConfigs().server + "/bookmarks/private", {url: req.url, private: req.private}, function(o) {
    log("[setPrivate] response:", o);
    sendResponse(o);
  });
}

function postComment(request, sendResponse) {
  log("[postComment] req:", request);
  ajax("POST", "http://" + getConfigs().server + "/comments/add", {
      url: request.url,
      title: request.title,
      text: request.text,
      permissions: request.permissions,
      parent: request.parent,
      recipients: request.recipients},
    function(o) {
      log("[postComment] resp:", o);
      sendResponse(o);
    });
}

function searchOnServer(request, sendResponse, tab) {
  var config = getConfigs();

  logEvent("search", "newSearch");

  if (!session) {
    log("[searchOnServer] no session");
    sendResponse({"session": null, "searchResults": [], "userConfig": config});
    return;
  }

  if (request.query === '') {
    sendResponse({"session": session, "searchResults": [], "userConfig": config});
    return;
  }

  ajax("GET", "http://" + config.server + "/search", {
      term: request.query,
      maxHits: config.max_res * 2,
      lastUUI: request.lastUUID,
      context: request.context,
      kifiVersion: chrome.app.getDetails().version},
    function(results) {
      log("[searchOnServer] results:", results);
      sendResponse({"session": session, "searchResults": results, "userConfig": config});
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
  log("[onPageLoad] tab:", tab);
  logEvent("extension", "pageLoad");

  checkWhetherKept(tab.url, function(isKept) {
    setPageIcon(tab, isKept);

    if (restrictedUrlPatternsForHover.some(function(e) {return tab.url.indexOf(e) >= 0})) {
      log("[onPageLoad] restricted:", tab.url);
      return;
    } else if (userHistory.exists(tab.url)) {
      log("[onPageLoad] recently visited:", tab.url);
      return;
    }
    userHistory.add(tab.url);

    if (isKept) {
      log("[onPageLoad] already kept:", tab.url);
    } else {
      var sliderDelaySec = getConfigs().hover_timeout;
      if (sliderDelaySec > 0) {
        chrome.tabs.sendMessage(tab.id, {type: "auto_show_after", ms: sliderDelaySec * 1000});
      }
    }
  });
}

// Kifi icon in location bar
chrome.pageAction.onClicked.addListener(function(tab) {
  log("button clicked", tab);
  chrome.tabs.sendMessage(tab.id, {type: "button_click"});
});

function checkWhetherKept(url, callback) {
  log("[checkWhetherKept] url:", url);
  if (!session) {
    log("[checkWhetherKept] no session");
    return;
  }

  ajax("GET", "http://" + getConfigs().server + "/bookmarks/check", {uri: url}, function done(o) {
    callback(o.user_has_bookmark);
  }, function fail(xhr) {
    log("[checkWhetherKept] error:", xhr.responseText);
    callback(false);
  });
}

function setPageIcon(tab, kept) {
  log("[setPageIcon] tab:", tab);
  chrome.windows.get(tab.windowId, function(win) {
    if (win.type == "normal") {
      chrome.pageAction.setIcon({tabId: tab.id, path: kept ? "icons/kept.png" : "icons/keep.png"});
      chrome.pageAction.show(tab.id);
    }
  });
}

function require(tabId, details, callback) {
  var scripts = details.scripts.reduce(function(a, s) {
    return a.concat(transitiveClosure(s));
  }, []).filter(unique);
  var styles = scripts.reduce(function(a, s) {
    a.push.apply(a, styleDeps[s]);
    return a;
  }, []);
  var injected = details.injected || {};
  injectAll(chrome.tabs.insertCSS.bind(chrome.tabs), styles, function() {
    injectAll(chrome.tabs.executeScript.bind(chrome.tabs), scripts, function() {
      chrome.tabs.executeScript(tabId, {code: "injected=" + JSON.stringify(injected)}, callback);
    });
  });

  function transitiveClosure(path) {
    var deps = scriptDeps[path];
    if (deps) {
      deps = deps.reduce(function(a, s) {
        return a.concat(transitiveClosure(s));
      }, []);
      deps.push(path);
      return deps.filter(unique);
    } else {
      return [path];
    }
  }

  function unique(value, index, array) {
    return array.indexOf(value) == index;
  }

  function injectAll(inject, paths, callback) {
    if (paths && paths.length) {
      var n = 0;
      paths.forEach(function(path) {
        if (!injected[path]) {
          log("[require] tab", tabId, path);
          inject(tabId, {file: path}, function() {
            injected[path] = true;
            if (++n == paths.length) {
              callback();
            }
          });
        } else if (++n == paths.length) {
          callback();
        }
      });
    } else {
      callback();
    }
  }
}

function postBookmarks(supplyBookmarks, bookmarkSource) {
  log("[postBookmarks]");
  supplyBookmarks(function(bookmarks) {
    log("[postBookmarks] bookmarks:", bookmarks);
    ajax("POST", "http://" + getConfigs().server + "/bookmarks/add", {
        bookmarks: bookmarks,
        source: bookmarkSource},
      function(o) {
        log("[postBookmarks] resp:", o);
      });
  });
}

chrome.tabs.onActivated.addListener(function(info) {
  log("[onActivated] tab info:", info);
  //maybeShowPageIcon(info.tabId, info.windowId);
});

chrome.tabs.onUpdated.addListener(function(tabId, change, tab) {
  log("[onUpdated] tab:", tab, change);
  if (change.url) {
    setPageIcon(tab, false);
  }
  if (change.status == "complete" && /^http/.test(tab.url)) {
    onPageLoad(tab);
  }
});

function getFullyQualifiedKey(key) {
  return (localStorage["env"] || "production") + "_" + key;
}

function removeFromConfigs(key) {
  localStorage.removeItem(getFullyQualifiedKey(key));
}

function setConfigs(key, value) {
  var prev = localStorage[getFullyQualifiedKey(key)];
  if (value != null && prev !== String(value)) {
    log("[setConfigs]", key, " = ", value, " (was ", prev, ")");
    localStorage[getFullyQualifiedKey(key)] = value;
  }
}

function getConfigs() {
  try {
    var env = localStorage["env"];
    if (!env) {
      localStorage["env"] = env = "production";
    }

    var config = {
      "env": env,
      "server": env == "development" ? "dev.ezkeep.com:9000" : "keepitfindit.com",
      "kifi_installation_id": localStorage[getFullyQualifiedKey("kifi_installation_id")],
      "bookmark_id": localStorage[getFullyQualifiedKey("bookmark_id")],
      "hover_timeout": parseNonNegIntOr(localStorage[getFullyQualifiedKey("hover_timeout")], 10),
      "show_score": parseBoolOr(localStorage[getFullyQualifiedKey("show_score")], false),
      "max_res": parseNonNegIntOr(localStorage[getFullyQualifiedKey("max_res")], 5)};
    //log("loaded config:");
    //log(config);
    return config;
  } catch (e) {
    error(e);
  }
}

function parseNonNegIntOr(val, defaultValue) {
  var n = parseInt(val, 10);
  return isNaN(n) ? defaultValue : Math.abs(n);
}

function parseBoolOr(val, defaultValue) {
  return val === "yes" || val === true || val === "true" || defaultValue;
}

chrome.runtime.onInstalled.addListener(function(details) {
  log("[onInstalled]", details);
  logEvent("extension", details.reason);
  logEvent("extension", "started");
  removeFromConfigs("user"); // remove this line in early Feb or so
  authenticate(function() {
    log("[onInstalled] authenticated");
    if (details.reason == "install" || getConfigs().env == "development") {
      postBookmarks(chrome.bookmarks.getTree, "INIT_LOAD");
    }
  });
});

chrome.runtime.onStartup.addListener(function() {
  log("[onStartup]");
  logEvent("extension", "started");
  authenticate(function() {
    log("[onStartup] authenticated");
  });
});

// ===== Session management

var session;

function authenticate(callback) {
  if (getConfigs().env == "development") {
    openFacebookConnect();
  } else {
    startSession(openFacebookConnect);
  }

  function startSession(onFail) {
    log("[startSession]");
    var config = getConfigs();
    ajax("POST", "http://" + config.server + "/kifi/start", {
      installation: config.kifi_installation_id,
      version: chrome.app.getDetails().version,
      // platform: navigator.platform,
      // language: navigator.language,
      agent: navigator.userAgent || navigator.appVersion || navigator.vendor},
    function done(data) {
      log("[startSession] done:", data);
      logEvent("extension", "authenticated");

      session = data;
      setConfigs("kifi_installation_id", data.installationId);

      // Locate or create KeepIt bookmark folder.
      getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
        setConfigs("bookmark_id", info.keepItId);
      });

      callback();
    }, function fail(xhr) {
      log("[startSession] xhr failed:", xhr);
      if (onFail) onFail();
    });
  }

  function openFacebookConnect() {
    log("[openFacebookConnect]");
    var popupTabId;
    chrome.tabs.onUpdated.addListener(function(tabId, changeInfo, tab) {
      if (changeInfo.status == "loading" && tabId == popupTabId && tab.url == "http://" + getConfigs().server + "/#_=_") {
        log("[openFacebookConnect] closing tab ", tabId);
        chrome.tabs.remove(tabId);
        popupTabId = null;

        startSession();
      }
    });

    chrome.windows.create({'url': 'http://' + getConfigs().server + '/authenticate/facebook', 'type': 'popup', 'width' : 1020, 'height' : 530}, function(win) {
      popupTabId = win.tabs[0].id
    });
  }
}

function deauthenticate(callback) {
  log("[deauthenticate]");
  session = null;
  chrome.windows.create({type: "popup", url: "http://" + getConfigs().server + "/session/end", width: 200, height: 100}, function(win) {
    log("[deauthenticate] created popup:", win);
    callback();
  });
}
