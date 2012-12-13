log("background page kicking in!");

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

// ===== Queries with hard-coded results

var magicQueries = [];
try {
  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function() {
    if (xhr.readyState == 4) {
      magicQueries = JSON.parse(xhr.response);
    }
  }
  xhr.open("GET", chrome.extension.getURL("data/magicQueries.json"), true);
  xhr.send();
} catch(e) {
  error(e, "loading magic queries");
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
var eventFamilies = ["slider","search","extension","account","notification"].reduce(function(o, f) {o[f] = true; return o}, {});

function logEvent(eventFamily, eventName, metaData, prevEvents) {
  if (!eventFamilies[eventFamily]) {
    log("[logEvent] Invalid event family", eventFamily);
    return;
  }
  var event = {
      "time": new Date().getTime(),
      "eventFamily": eventFamily, /* Currently, has to be one of: slider, search, extension */
      "eventName": eventName, /* Any key for this event */
      "metaData": metaData || {}, /* Any js object that you would like to attach to this event. i.e., number of total results shown, which result was clicked, etc. */
      "prevEvents": prevEvents || [] /* a list of previous ExternalId[Event]s that are associated with this action. !!!: The frontend determines what is associated with what. */
    }
  _eventLog.push(event);
}

var eventLogDelay = 2000;
setTimeout(function maybeSend() {
  if (_eventLog.length) {
    var config = getConfigs();
    var eventLog = {
      "version": 1,
      "time": new Date().getTime(),
      "installId": config.kifi_installation_id, /* User's ExternalId[KifiInstallation] */
      "events": _eventLog
    }
    log("[logEvent:maybeSend] Sending event log: ", JSON.parse(JSON.stringify(eventLog)));

    $.post("http://" + config.server + "/users/events", {
      payload: JSON.stringify(eventLog)
    }).success(function(data) {
      log("[logEvent:maybeSend] Event log sent. Response: ", data)
    }).error(function(xhr) {
      error(Error("[logEvent:maybeSend] Event log sending failed"));
      log("[logEvent:maybeSend] ", xhr.responseText);
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
// TODO: onRequest => onMessage, see http://stackoverflow.com/questions/11335815/chrome-extensions-onrequest-sendrequest-vs-onmessage-sendmessage
chrome.extension.onRequest.addListener(function(request, sender, sendResponse) {
  var tab = sender.tab;
  log("[onRequest] handling", request, "for", tab && tab.id);
  try {
    switch (request && request.type) {
      case "page_load":
        onPageLoad(request, sendResponse, tab);
        break;
      case "get_keeps":
        searchOnServer(request, sendResponse, tab);
        break;
      case "add_bookmarks":
        getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
          addKeep(info, request, sendResponse, tab);
        });
        break;
      case "unkeep":
        getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
          removeKeep(info, request, sendResponse, tab);
        });
        break;
      case "set_private":
        getBookmarkFolderInfo(getConfigs().bookmark_id, function(info) {
          setPrivate(info, request, sendResponse, tab);
        });
        break;
      case "follow":
        $.ajax("http://" + getConfigs().server + "/comments/follow?url=" + encodeURIComponent(tab.url),
          {"type": request.follow ? "POST" : "DELETE"});
        break;
      case "get_conf":
        sendResponse(getConfigs());
        break;
      case "set_conf":
        setConfigs(request.key, request.value);
        sendResponse();
        break;
      case "remove_conf":
        setConfigs(request.key);
        sendResponse();
        break;
      case "upload_all_bookmarks":
        uploadAllBookmarks();
        break;
      case "set_page_icon":
        setPageIcon(tab.id, request.is_kept);
        break;
      case "get_slider_info":
        $.get("http://" + getConfigs().server + "/users/slider?url=" + encodeURIComponent(tab.url), sendResponse);
        break;
      case "get_slider_updates":
        $.get("http://" + getConfigs().server + "/users/slider/updates?url=" + encodeURIComponent(tab.url), sendResponse);
        break;
      case "log_event":
        if(!request.eventFamily || !request.eventName) {
          log("Bad event", request)
          break;
        }
        logEvent(request.eventFamily, request.eventName, request.metaData || {}, request.prevEvents || []);
        break;
      case "get_comments":
        $.get("http://" + getConfigs().server +
          (request.kind == "public" ? "/comments/public" : "/messages/threads") +
          (request.commentId ? "/" + request.commentId : "?url=" + encodeURIComponent(tab.url)),
          sendResponse);
        break;
      case "post_comment":
        postComment(request, sendResponse);
        break;
      case "get_friends":
        $.get("http://" + getConfigs().server + "/users/friends", sendResponse);
        break;
      default:
        log("Ignoring unknown message");
    }
    // Return nothing to let the connection be cleaned up.
  } catch (e) {
    error(e);
  }
  log("[onRequest] done", request);
});

// Finds KiFi bookmark folder by id (if provided) or by name in the Bookmarks Bar,
// or else creates it there. Ensures that it has "public" and "private" subfolders.
// Passes an object with the three folder ids to the callback.
function getBookmarkFolderInfo(keepItBookmarkId, callback) {
  log("[getBookmarkFolderInfo]");

  if (keepItBookmarkId) {
    chrome.bookmarks.get(keepItBookmarkId, function(bm) {
      if (bm.length) {
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
      chrome.bookmarks.create({parentId: keepIt.id, title: "private"}, function(bm) {
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

function uploadAllBookmarks() {
  log("going to upload all my bookmarks to server");
  if (!getUser()) {
    log("Can't upload bookmarks, no user info!");
    return;
  }
  // TODO: actually upload all bookmarks
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
  log("removing bookmark:", req.url, tab);

  var userConfig = getConfigs();
  var userInfo = userConfig.user;
  if (!userInfo) {
    log("No user info! Can't remove keep!");
    return;
  }

  chrome.bookmarks.search(req.url, function(bm) {
    bm.forEach(function(bm) {
      if (bm.url == req.url && (bm.parentId == bmInfo.publicId || bm.parentId == bmInfo.privateId)) {
        chrome.bookmarks.remove(bm.id);
      }
    });
  });

  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function() {
    if (xhr.readyState == 4) {
      log("[removeKeep] response:", xhr.response);
      sendResponse(xhr.response);
    }
  }
  xhr.open("POST", "http://" + userConfig.server + "/bookmarks/remove?uri=" + encodeURIComponent(req.url), true);
  xhr.send();
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

  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function() {
    if (xhr.readyState == 4) {
      log("[setPrivate] response:", xhr.response);
      sendResponse(xhr.response);
    }
  }
  xhr.open("POST", "http://" + getConfigs().server + "/bookmarks/private" +
    "?uri=" + encodeURIComponent(req.url) +
    "&isPrivate=" + +req.private,
    true);
  xhr.send();
}

function postComment(request, sendResponse) {
  log("posting comment: ", request);

  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function() {
    if (xhr.readyState == 4) {
      var response = JSON.parse(xhr.response);
      log("POSTED! ", response);
      sendResponse(response);
    }
  }
  var userConfigs = getConfigs();
  if (!userConfigs || !userConfigs.user) {
    log("No user info! Can't post comment!");
    return;
  }
  var parent = request.parent || "";
  var recipients = request.recipients || "";

  xhr.open("POST", "http://" + userConfigs.server + "/comments/add" +
    "?url=" + encodeURIComponent(request.url) +
    "&title=" + encodeURIComponent(request.title) +
    "&text=" + encodeURIComponent(request.text) +
    "&permissions=" + request.permissions +
    "&parent=" + parent +
    "&recipients=" + recipients,
    true);
  xhr.send();
}

function searchOnServer(request, sendResponse, tab) {
  var userConfigs = getConfigs();

  logEvent("search","newSearch");

  if (!getUser()) {
    log("No facebook, can't search!");
    sendResponse({"userInfo": null, "searchResults": [], "userConfig": userConfigs});
    return;
  }

  for (var i = 0; i < magicQueries.length; i++) {
    console.log("checking: ", magicQueries[i]);
    if (magicQueries[i].query === request.query) {
      log("Intercepting query: " + request.query);
      sendResponse({"userInfo": userConfigs.user, "searchResults": magicQueries[i].results, "userConfig": userConfigs});
      return;
    }
  }

  if (request.query === '') {
    sendResponse({"userInfo": userConfigs.user, "searchResults": [], "userConfig": userConfigs});
    return;
  }

  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function() {
    if (xhr.readyState == 4) {
      log("[searchOnServer] xhr response:", tab);
      log(xhr.response, tab);
      sendResponse({"userInfo": userConfigs.user, "searchResults": JSON.parse(xhr.response), "userConfig": userConfigs});
    }
  }
  xhr.open("GET",
   'http://' + userConfigs.server + '/search' +
      '?term=' + encodeURIComponent(request.query) +
      '&maxHits=' + userConfigs.max_res * 2 +
      '&lastUUI=' + (request.lastUUID || "") +
      '&context=' + encodeURIComponent(request.context || "") +
      '&kifiVersion=' + currVersion,
    true);
  xhr.send();
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

function onPageLoad(request, sendResponse, tab) {
  log("[onPageLoad]", request, tab);
  if (!getUser()) {
    log("[onPageLoad] No facebook configured!")
    return;
  }
  setPageIcon(tab.id, false);
  logEvent("extension", "pageLoad");

  if (restrictedUrlPatternsForHover.some(function(e) {return tab.url.indexOf(e) >= 0})) {
    log("[onPageLoad] restricted:", tab.url);
    return;
  }

  checkWhetherKept(tab.url, function(isKept) {
    setPageIcon(tab.id, isKept);

    if (userHistory.exists(tab.url)) {
      return;
    }
    userHistory.add(tab.url);

    if (isKept) {
      log("[onPageLoad] already kept:", tab.url);
    } else {
      var sliderDelaySec = getConfigs().hover_timeout;
      if (sliderDelaySec > 0) {
        sendResponse(sliderDelaySec * 1000);
      }
    }
  });
}

// Kifi icon in location bar
chrome.pageAction.onClicked.addListener(function(tab) {
  log("button clicked", tab);
  chrome.tabs.sendRequest(tab.id, {type: "button_click"});
});

function checkWhetherKept(location, callback) {
  log("checking if user has already bookmarked page: " + location);
  var userConfig = getConfigs();
  if (!userConfig || !userConfig.user || !userConfig.user.keepit_external_id) {
    log("Can't check if already kept, no user info!");
    return;
  }

  $.getJSON("http://" + userConfig.server + "/bookmarks/check" +
    "?uri=" + encodeURIComponent(location))
  .success(function(data) {
    callback(data.user_has_bookmark);
  }).error(function(xhr) {
    log("remoteIsAlreadyKept error:", xhr.responseText);
    callback(false);
  });
}

function setPageIcon(tabId, kept) {
  log("[setPageIcon] tab ", tabId);
  chrome.tabs.get(tabId, function(tab) {
    log("[setPageIcon] tab ", tab);
    chrome.pageAction.setIcon({"tabId": tabId, "path": kept ? "icons/kept.png" : "icons/keep.png"});
    chrome.pageAction.show(tabId);
  });
}

function maybeShowPageIcon(tabId, windowId) {
  chrome.tabs.query({active: true, windowId: windowId, windowType: "normal"}, function(tabs) {
    log("[maybeShowPageIcon] tabs: ", tabs);
    tabs.forEach(function(tab) {
      if (tab.id == tabId && tab.url.match(/^http/)) {
        log("[maybeShowPageIcon] showing on ", tab);
        chrome.pageAction.show(tab.id);
      }
    });
  });
}

function postBookmarks(supplyBookmarks, bookmarkSource) {
  log("posting bookmarks...");
  supplyBookmarks(function(bookmarks) {
    log("bookmarks: ", bookmarks);
    if (!getUser()) {
      log("Can't post bookmark(s), no user info!");
      return;
    }
    var userConfigs = getConfigs();
    var xhr = new XMLHttpRequest();
    xhr.onreadystatechange = function() {
      if (xhr.readyState == 4) {
        log("[postBookmarks] response: ", xhr.responseText);
      }
    }
    xhr.open("POST", 'http://' + userConfigs.server + '/bookmarks/add', true);
    xhr.setRequestHeader("Content-Type","application/json; charset=utf-8");
    xhr.send(JSON.stringify({
      "bookmarks": bookmarks,
      "bookmark_source": bookmarkSource}));
    log("posted bookmarks");
  });
}

chrome.tabs.onActivated.addListener(function(info) {
  log("[onActivated] tab info ", info);
  maybeShowPageIcon(info.tabId, info.windowId);
});

chrome.tabs.onUpdated.addListener(function(tabId, change, tab) {
  log("[onUpdated] tab ", tab, change);
  if (tab.active && tab.url.match(/^http/)) {
    maybeShowPageIcon(tabId, tab.windowId);
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
  if (prev !== String(value)) {
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
      "upload_on_start": parseBoolOr(localStorage[getFullyQualifiedKey("upload_on_start")], false),
      "max_res": parseNonNegIntOr(localStorage[getFullyQualifiedKey("max_res")], 5),
      "user": parseJsonObjOr(localStorage[getFullyQualifiedKey("user")])};
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

function parseJsonObjOr(val, defaultValue) {
  if (/^{/.test(val)) {
    try {
      return JSON.parse(val);
    } catch (e) {
    }
  }
  return defaultValue;
}

function getUser() {
  var user = parseJsonObjOr(localStorage[getFullyQualifiedKey("user")]);
  // Return undefined if it is not a complete user.
  return user && user.keepit_external_id && user.facebook_id && user.name && user.avatar_url && user;
}

// Check if the version has changed.
var currVersion = chrome.app.getDetails().version;
var prevVersion = getConfigs().version;
if (currVersion != prevVersion) {
  if (!prevVersion) {
    log("Extension Installed");
  } else {
    log("Extension Updated");
  }
  setConfigs("version", currVersion);
}

function startHandShake(onFail) {
  log("[startHandShake]");
  var config = getConfigs();
  $.post("http://" + config.server + "/kifi/start", {
    installation: config.kifi_installation_id || "",
    version: currVersion,
    // platform: navigator.platform,
    // language: navigator.language,
    agent: navigator.userAgent || navigator.appVersion || navigator.vendor
  }).success(onAuthenticate).error(function(xhr) {
    error(Error("handshake failed"));
    log(xhr.responseText);
    if (onFail) onFail();
  });
}

function openFacebookConnect() {
  var popupTabId;
  chrome.tabs.onUpdated.addListener(function(tabId, changeInfo, tab) {
    if (changeInfo.status == "loading" && tabId == popupTabId && tab.url == "http://" + getConfigs().server + "/#_=_") {
      log("[openFacebookConnect] closing tab ", tabId);
      chrome.tabs.remove(tabId);
      popupTabId = null;

      startHandShake();
    }
  });

  chrome.windows.create({'url': 'http://' + getConfigs().server + '/authenticate/facebook', 'type': 'popup', 'width' : 1020, 'height' : 530}, function(win) {
    popupTabId = win.tabs[0].id
  });
}

function onAuthenticate(data) {
  log("[onAuthenticate]", data);
  setConfigs("user", JSON.stringify({
    facebook_id: data.facebookId,
    keepit_external_id: data.userId,
    avatar_url: data.avatarUrl,
    name: data.name}));
  setConfigs("kifi_installation_id", data.installationId);

  var config = getConfigs();
  if (!prevVersion || config.upload_on_start) {
    log("loading bookmarks to the server");
    postBookmarks(chrome.bookmarks.getTree, prevVersion ? "PLUGIN_START" : "INIT_LOAD");
  } else {
    log("[onAuthenticate] NOT uploading bookmarks");
  }

  // Locate or create KeepIt bookmark folder.
  getBookmarkFolderInfo(config.bookmark_id, function(info) {
    setConfigs("bookmark_id", info.keepItId);
  });

  log("[onAuthenticate] done");
  logEvent("extension", "authenticated");
}

if (getConfigs().env == "development" || !getUser()) {
  removeFromConfigs("user");
  openFacebookConnect();
} else {
  startHandShake(function() {
    log("User does not appear to be logged in remote. Refreshing data...");
    removeFromConfigs("user");
    openFacebookConnect();
  });
}

logEvent("extension","started");
