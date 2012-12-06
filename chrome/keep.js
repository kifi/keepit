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
  xhr.open("GET", chrome.extension.getURL("magicQueries.json"), true);
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

function logEvent(payload) {
  _eventLog.push({"time": new Date().getTime(), "event": payload});
}

var eventLogDelay = 2000;
setTimeout(function maybeSend() {
  if (_eventLog.length) {
    log("Pretending to send event log: ", {clientTime: new Date().getTime(), log: _eventLog});

    // TODO: actually post event log to server

    _eventLog.length = 0;
    eventLogDelay = Math.round(Math.max(Math.sqrt(eventLogDelay), 2000));
  } else {
    eventLogDelay = Math.min(eventLogDelay * 2, 20000);
  }
  setTimeout(maybeSend, eventLogDelay);
}, 4000);

// ===== Message handling

function postBookmarks(supplyBookmarks, bookmarkSource) {
  log("posting bookmarks...");
  supplyBookmarks(function(bookmarks) {
    log("bookmarks:");
    log(bookmarks);
    if (!hasKeepitIdAndFacebookId()) {
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
    xhr.send(JSON.stringify({
      "bookmarks": bookmarks,
      "user_info": userConfigs.user,
      "bookmark_source": bookmarkSource}));
    log("posted bookmarks");
  });
}

// Listen for the content script to send a message to the background page.
// TODO: onRequest => onMessage, see http://stackoverflow.com/questions/11335815/chrome-extensions-onrequest-sendrequest-vs-onmessage-sendmessage
chrome.extension.onRequest.addListener(function(request, sender, sendResponse) {
  var tab = sender.tab;
  log("[onRequest] handling", request, "for", tab && tab.id);
  try {
    switch (request && request.type) {
      case "init_page":
        initPage(request, sendResponse, tab);
        break;
      case "get_keeps":
        searchOnServer(request, sendResponse, tab);
        break;
      case "get_user_info":
        sendResponse(getConfigs().user);
        break;
      case "add_bookmarks":
        getBookmarks(function(bookmarks) {
          addKeep(bookmarks, request, sendResponse, tab);
        });
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
      case "check_hover_existed":
        if (request.kifi_hover === false) { showSlider(tab.id); }
        break;
      case "set_page_icon":
        setPageIcon(tab.id, request.is_kept);
        break;
      case "open_slider":
        showSlider(tab.id);
        break;
      case "log_event":
        logEvent(request.event);
        break;
      case "post_comment":
        postComment(request, sendResponse);
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

function uploadAllBookmarks() {
  log("going to upload all my bookmarks to server");
  if (!hasKeepitIdAndFacebookId()) {
    log("Can't upload bookmarks, no user info!");
    return;
  }
  // TODO: actually upload all bookmarks
}

function addKeep(bookmarks, request, sendResponse, tab) {
  log("creating bookmark. private: " + request.private + " tile " + request.title, tab);
  var parent = (request.private) ? bookmarks.private : bookmarks.public;
  var isPrivate = request.private ? true : false; // Forcing actual `true' and `false'
  var bookmark = {'parentId': parent.id, 'title': request.title, 'url': request.url};
  chrome.bookmarks.create(bookmark, function(created) {
    try {
      sendResponse(created);
      bookmark.isPrivate = isPrivate;
      postBookmarks(function(f) {f([bookmark])}, "HOVER_KEEP");
    } catch (e) {
      error(e);
    }
  });
}

function removeKeep(bookmarks, request, sendResponse, tab) {
  log("removing bookmark: ", request.externalId, tab);
  sendResponse(created);

  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function() {
    if (xhr.readyState == 4) {
      log("removed bookmark. " + xhr.response);
    }
  }
  var userConfig = getConfigs();
  var userInfo = userConfig.user;
  if (!userInfo) {
    log("No user info! Can't remove keep!");
    return;
  }

  xhr.open("POST", 'http://' + userConfig.server + '/bookmarks/remove/?externalId=' + userInfo["keepit_external_id"] + "&externalBookmarkId=" + request.externalId, true);
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

  if (!hasKeepitIdAndFacebookId()) {
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
      '&externalId=' + userConfigs.user.keepit_external_id +
      '&maxHits=' + userConfigs.max_res * 2 +
      '&lastUUI=' + (request.lastUUID || "") +
      '&context=' + (request.context || "") +
      '&kifiVersion=' + getVersion(),
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

function initPage(request, sendResponse, tab) {
  log("[initPage]", request, tab);
  if (!hasKeepitIdAndFacebookId()) {
    log("[initPage] No facebook configured!")
    return;
  }
  setPageIcon(tab.id, false);
  var pageLocation = request.location;

  if (restrictedUrlPatternsForHover.some(function(e) {return pageLocation.indexOf(e) >= 0})) {
    log("[initPage] restricted ", tab.url);
    return;
  }

  var hoverTimeout = getConfigs().hover_timeout;

  checkWhetherKept(pageLocation, function(isKept) {
    setPageIcon(tab.id, isKept);

    if (userHistory.exists(pageLocation)) {
      return;
    }
    userHistory.add(pageLocation);

    if (isKept) {
      log("[initPage] already kept ", pageLocation);
    } else if (hoverTimeout > 0) {
      setTimeout(function autoShowSlider() {
        log("[autoShowSlider] tab", tab.id, pageLocation);
        chrome.tabs.executeScript(tab.id, {
          // We don't slide in automatically if the slider has already been shown (manually).
          code: "chrome.extension.sendRequest({type:'check_hover_existed',kifi_hover:window.kifi_hover||false});"
        });
      }, hoverTimeout * 1000);
    }
  });
}

function showSlider(tabId) {
  log("[showSlider] tab ", tabId);
  chrome.tabs.sendRequest(tabId, {type: "show_hover"});
}

// Kifi icon in location bar
chrome.pageAction.onClicked.addListener(function(tab) {
  showSlider(tab.id);
});

function checkWhetherKept(location, callback) {
  log("checking if user has already bookmarked page: " + location);
  var userConfig = getConfigs();
  if (!userConfig || !userConfig.user || !userConfig.user.keepit_external_id) {
    log("Can't check if already kept, no user info!");
    return;
  }

  $.getJSON("http://" + userConfig.server + "/bookmarks/check" +
    "?externalId=" + userConfig.user.keepit_external_id +
    "&uri=" + encodeURIComponent(location))
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
    chrome.pageAction.setIcon({"tabId": tabId, "path": kept ? "kept.png" : "keepit.png"});
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
  log("setting config key " + key + " with " + value + ", prev value is ", localStorage[getFullyQualifiedKey(key)]);
  localStorage[getFullyQualifiedKey(key)] = value;
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

function addNewKeep() {
  alert("did nothing!")
}

function onInstall() {
  log("Extension Installed");
}

function onUpdate() {
  log("Extension Updated");
}

function getVersion() {
  return chrome.app.getDetails().version;
}

function startHandShake(callback) {
  log("starting handShake");
  $.post("http://" + getConfigs().server + "/kifi/start", {
    installation: getConfigs().kifi_installation_id,
    version: getVersion(),
    // platform: navigator.platform,
    // language: navigator.language,
    agent: navigator.userAgent || navigator.appVersion || navigator.vendor
  }).success(callback).error(function(xhr) {
    log(xhr.responseText);
    callback();
  });
}

function hasKeepitIdAndFacebookId() {
  var user = getConfigs().user;
  if (!user) return false;
  return user.keepit_external_id && user.facebook_id && user.name && user.avatar_url;
}

// Check if the version has changed.
var currVersion = getVersion();
var prevVersion = getConfigs().version;
if (currVersion != prevVersion) { // Check if we just installed this extension.
  if (typeof prevVersion == 'undefined' || prevVersion == '') { //install
    onInstall();
  } else { //update
    onUpdate();
  }
  setConfigs('version', currVersion);
}

var popup = null;

function openFacebookConnect() {
  chrome.tabs.onUpdated.addListener(function(tabId, changeInfo, tab) {
    if (changeInfo.status == "loading") {
      if (tabId === popup && tab.url === "http://" + getConfigs().server + "/#_=_") {
        popup = undefined;
        startHandShake(function(o) {
          if (o) {
            log("got handshake data: ", o);
            setConfigs("user", JSON.stringify({
              facebook_id: o.facebookId,
              keepit_external_id: o.userId,
              avatar_url: o.avatarUrl,
              name: o.name}));
            setConfigs("kifi_installation_id", o.installationId);
            postBookmarks(chrome.bookmarks.getTree, "INIT_LOAD");
            log("handshake done");
          } else {
            log("handshake failed");
          }
          log("[openFacebookConnect] closing tab ", tabId);
          chrome.tabs.remove(tabId);
        });
      }
    }
  });

  chrome.windows.create({'url': 'http://' + getConfigs().server + '/authenticate/facebook', 'type': 'popup', 'width' : 1020, 'height' : 530}, function(win) {
    popup = win.tabs[0].id
  });
}

function resetUserObjectIfInDevMode() {
  var config = getConfigs();
  if (config.env == "development") {
    log("dev mode, removing user ", config.user);
    removeFromConfigs("user");
  }
}

resetUserObjectIfInDevMode();

if (!hasKeepitIdAndFacebookId()) {
  log("open facebook connect - till it is closed keepit is (suppose) to be disabled");
  setConfigs("user", "{}");
  openFacebookConnect();
} else {
  log("find user info in local storage");

  startHandShake(function(data) {
    if (data == null) {
      // Need to refresh Facebook info
      log("User does not appear to be logged in remote. Refreshing data...");
      setConfigs("user", "{}");
      openFacebookConnect();
    } else {
      log("User logged in, loading bookmarks");
      setConfigs("kifi_installation_id", data.installationId);
      var config = getConfigs();
      log(config);
      if (config.upload_on_start === true) {
        log("loading bookmarks to the server");
        postBookmarks(chrome.bookmarks.getTree, "PLUGIN_START");//posting bookmarks even when keepit id is found
      } else {
        log("NOT loading bookmarks to the server");
      }
      getBookmarks();
    }
  });
}

logEvent("Plugin started!");
