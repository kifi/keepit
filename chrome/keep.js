var magicQueries = [];
// End Dummy results

function log(message) {
  console.log("[" + new Date().getTime() + "] ", message);
}

function loadMagicQueries() {
  log("Loading magic!");
  try {
    var xhr = new XMLHttpRequest();
    xhr.onreadystatechange = function() {
      if (xhr.readyState == 4) {
        magicQueries = JSON.parse(xhr.response);
      }
    }
    var mq = chrome.extension.getURL('magicQueries.json');
    xhr.open("GET", mq, true);
    xhr.send();
  } catch(e) {
    log("magicQueries failed.");
  }
}

loadMagicQueries();

function UserHistory() {
  var HISTORY_SIZE = 200;
  this.history = new Array();
  this.add = function(uri) {
    log("adding " + uri + " to user history.");
    this.history.unshift(uri);
    if(history.length > HISTORY_SIZE) {
      history.pop();
    }
  }
  this.exists = function(uri) {
    log("current history:");
    log(this.history);

    for(var i=0;i<this.history.length;i++) {
      if(this.history[i] === uri) {
        log(uri + " already has been visited");
        return true;
      }
    }
    return false;
  }
}

var userHistory = new UserHistory();
var _eventLog = new Array();

function logEvent(payload) {
  _eventLog.push({
    "time": (new Date()).getTime(),
    "event": payload
  });
}

function sendEventLog() {
  var userConfig = getConfigs();
  var json = {
    "client_time": (new Date()).getTime(),
    "user_config": userConfig,
    "event_log": _eventLog
  }
  var tosend = JSON.stringify(json);
  console.log("Sending event log!", tosend);

  // var xhr = new XMLHttpRequest();
  // xhr.open("POST", 'http://' + userConfig.server + '/bookmarks/add', true);
  // xhr.send(tosend);

  _eventLog = new Array();
}

var eventLogDelay = 2000;
function eventLogTimer() {
  if(_eventLog.length > 0) {
    eventLogDelay = Math.round(Math.max(Math.sqrt(eventLogDelay), 2000));
    sendEventLog();
  }
  else {
    // Nothing to send...
    eventLogDelay = Math.min(eventLogDelay * 2, 20000);
  }
  setTimeout(eventLogTimer, eventLogDelay);
}

setTimeout(function() {
  eventLogTimer();
}, 4000);

function error(exception, message) {
  //debugger;
  var errMessage = exception.message;
  if(message) {
    errMessage = "[" + message + "] " + exception.message;
  }
  if (exception) {
    console.error(exception);
  }
  console.error(errMessage);
  console.error(exception.stack);
  //alert("exception: " + exception.message);
}

log("background page kicking in!");

function postBookmarks(bookmarksSupplier, bookmarkSource) {
  log("posging bookmarks...");
  try {
    var xhr = new XMLHttpRequest();
    xhr.onreadystatechange = function() {
      if (xhr.readyState == 4) {
        log("[postBookmarks] xhr response:");
        log(xhr.responseText);
      }
    }
    bookmarksSupplier(function(bookmarks){
      try {
        bookmarksPoster(bookmarks, bookmarkSource, xhr);
      } catch (e) {
        error(e);
      }
    });
  } catch (e) {
    error(e);
  }
}

function bookmarksPoster(bookmarks, bookmarkSource, xhr) {
  log("bookmarks:");
  log(bookmarks);
  if(!hasKeepitIdAndFacebookId()) {
    log("Can't post bookmark, no user info!");
    return;
  }
  var userConfigs = getConfigs();
  var json = {
    "bookmarks": bookmarks,
    "user_info": userConfigs.user,
    "bookmark_source": bookmarkSource
  }
  var tosend = JSON.stringify(json);
  xhr.open("POST", 'http://' + getConfigs().server + '/bookmarks/add', true);
  xhr.send(tosend);
  log("posted bookmarks");
}

function onRequest(request, sender, sendResponse) {
  log("new request...");
  try {
    if(typeof request === 'undefined' || typeof request.type === 'undefined' ) {
      log("Recieved an unknown message. Discarding.");
      log(request);
      log(tab);
      return;
    }
    var tab = sender.tab;
    log("on request with " + JSON.stringify(request), tab);
    // Show the page action for the tab that the sender (content script) was on.
    log("executing request " + request.type, tab);
    if (request.type === "init_page") {
      initPage(request, sendResponse, tab);
    } else if(request.type === "get_keeps") {
      searchOnServer(request, sendResponse, tab);
    } else if(request.type === "get_user_info") {
      sendResponse(getConfigs().user);
    } else if(request.type === "add_bookmarks") {
      getBookMarks(function(bookmarks) {
        addKeep(bookmarks, request, sendResponse, tab);
      });
    } else if (request.type == "get_conf") {
      var conf = getConfigs();
      sendResponse(conf);
    } else if (request.type == "set_conf") {
      setConfigs(request.key, request.value);
      sendResponse();
    } else if (request.type == "remove_conf") {
      setConfigs(request.key);
      sendResponse();
    } else if (request.type == "upload_all_bookmarks") {
      upload_all_bookmarks();
    }
    else if (request.type === "check_hover_existed") {
      if(request.kifi_hover === false) { addHover(tab.id); }
    }
    else if (request.type === "set_page_icon") {
      setPageIcon(tab.id, request.is_kept);
    }
    else if (request.type === "open_slider") {
      addHover(tab.id);
    }
    else if (request.type === "log_event") {
      logEvent(request.event);
    }
    else if (request.type === "post_comment") {
      postComment(request, sendResponse);
    }
    // Return nothing to let the connection be cleaned up.
  } catch (e) {
    error(e);
  }
  log("done request...");
}

function upload_all_bookmarks() {
  log("going to upload all my bookamrks to server");
  if(!hasKeepitIdAndFacebookId()) {
    log("Can't upload bookmarks, no user info!");
    return;
  }
  var userConfigs = getConfigs();
  getPicture(userConfigs.user);
  getKeepitId(userConfigs.user)
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
      postBookmarks(function (poster) {poster([bookmark]);}, "HOVER_KEEP");
    } catch (e) {
      error(e);
    }
  });
}

function removeKeep(bookmarks, request, sendResponse, tab) {
  log("removing bookmark. " + request.externalId, tab);
  sendResponse(created);

  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function() {
    if (xhr.readyState == 4) {
      log("removed bookmark. " + xhr.response);
    }
  }
  var userInfo = getConfigs().user;
  if(!userInfo) {
    log("No userinfo! Can't remove keep!");
    return;
  }

  xhr.open("POST", 'http://' + userConfig.server + '/bookmarks/remove/?externalId=' + userInfo["keepit_external_id"] + "&externalBookmarkId=" + request.externalId, true);
  xhr.send();

}

function postComment(request, sendResponse) {
  log("posting comment:");
  console.log(request);

  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function() {
    if (xhr.readyState == 4) {
      var response = JSON.parse(xhr.response);
      log("POSTED!" + response);
      console.log(response);
      sendResponse(response);
    }
  }
  var userConfigs = getConfigs();
  if(!userConfigs || !userConfigs.user) {
    log("No userinfo! Can't post comment!");
    return;
  }
  var parent = request.parent || "";
  var recipients = request.recipients || "";

  xhr.open("POST", 'http://' + userConfigs.server + '/comments/add?&url=' + encodeURIComponent(request.url) + 
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

  if(!userConfigs || !userConfigs.user || !userConfigs.user["keepit_external_id"] || !hasKeepitIdAndFacebookId()) {
    log("No facebook, can't search!");
    sendResponse({"userInfo": null, "searchResults": [], "userConfig": userConfigs});
    return;
  }
  // Dummy results injection
  for(i=0;i<magicQueries.length;i++) {
    console.log("checking: ", magicQueries[i])
    if(magicQueries[i].query === request.query) {
      log("Intercepting query: " + request.query);
      sendResponse({"userInfo": userConfigs.user, "searchResults": magicQueries[i].results, "userConfig": userConfigs});
      return;
    }
  }
  // Dummy results injection

  if(request.query === '') {
    sendResponse({"userInfo": userConfigs.user, "searchResults": [], "userConfig": userConfigs});
    return;
  }

  var xhr = new XMLHttpRequest();
  xhr.onreadystatechange = function() {
    if (xhr.readyState == 4) {
      log("[searchOnServer] xhr response:", tab);
      log(xhr.response, tab);
      var searchResults = $.parseJSON(xhr.response);
      log("sending res")
      if (searchResults.length === 0) {
        sendResponse({"userInfo": userConfigs.user, "searchResults": [], "userConfig": userConfigs});
        return;
      }
      /*var maxRes = getConfigs()["max_res"];
      if (searchResults.length > maxRes) {
        searchResults = searchResults.slice(0, maxRes);
      }*/
      log("Sending response!")
      sendResponse({"userInfo": userConfigs.user, "searchResults": searchResults, "userConfig": userConfigs});
    }
  }
  var term = encodeURIComponent(request.query);
  var externalId = userConfigs.user["keepit_external_id"];
  var lastUUID = typeof request.lastUUID === 'undefined' ? '' : request.lastUUID;
  var context = typeof request.context === 'undefined' ? '' : request.context;
  xhr.open("GET", 'http://' + userConfigs.server + '/search?term=' + term + '&externalId=' + externalId + '&maxHits=' + userConfigs["max_res"]*2 + '&lastUUI=' + lastUUID + '&context=' + context, true);
  xhr.send();
}

function catching(block) {
  try {
    block();
  } catch (e) {
    error(e);
  }
}

var restrictedUrlPatternsForHover = [
  "www.facebook.com",
  "keepitfindit.com",
  "ezkeep.com",
  "localhost:",
  "maps.google.com",
  "google.com*tbm=isch",
  "www.google.com",
  "google.com"
]

function initPage(request, sendResponse, tab) {
  try {
    log("init page!!!!!!!!");
    if(!hasKeepitIdAndFacebookId()) {
      log("No facebook configured!")
      return;
    }
    setPageIcon(tab.id, false);

    var restrictedElements = $.grep(restrictedUrlPatternsForHover, function(e, i){
      return request.location.indexOf(e) >= 0;
    });
    if (restrictedElements.length > 0) {
      log("restricted hover page: " + restrictedElements);
      return;
    }

    if (request.isGoogle === true) {
      log("init_page for Google pages");
    } else if (request.isGoogle === false) {

      var pageLocation = request.location;

      log("injecting hover: " + request, tab);
      var userConf =  getConfigs();
      var hoverTimeout = userConf["hover_timeout"];


      if(typeof userConf.user === "undefined" || typeof userConf.user.keepit_external_id === "undefined" || typeof userConf.user.facebook_id === "undefined") {
        // User does not have facebook configured.
        log("User does not have facebook configured.");
        openFacebookConnect();
        return;
      }

      remoteIsAlreadyKept(pageLocation, function(isKept) {
        if(isKept) {
          setPageIcon(tab.id, true);
          attachShortcut();
          log("hiding hover cause URL " + pageLocation + " already kept", tab);
          if(userHistory.exists(pageLocation) === true) {
            return;
          }

          userHistory.add(pageLocation);
        }
        else {
          setPageIcon(tab.id, false);
          attachShortcut();
          if(userHistory.exists(pageLocation) === true) {
            return;
          }

          userHistory.add(pageLocation);
          if (hoverTimeout > 0) {
            setTimeout(function() {
              // For the auto-slide in, we check to see if the hover has existed on this page before.
              checkHoverExisted(tab.id);
            }, hoverTimeout * 1000);
          }
        }
      });
    } else {
      throw Error("request isGoogle is not well defined: " + request.isGoogle);
    }
  } catch (e) {
    error(e);
  }
}

function attachShortcut(tabid) {
  /*safeExecuteScript(tabid, {
    code: "$(window).keydown(function(e) { if(e.metaKey && e.keyCode == 75) { chrome.extension.sendRequest({'type':'open_slider'}); } });"
  });*/
}

function checkHoverExisted(tabid) {
  safeExecuteScript(tabid, {
    code: "chrome.extension.sendRequest({'type': 'check_hover_existed', 'kifi_hover': typeof window.kifi_hover !== 'undefined'});"
  });
}

function safeExecuteScript(tabid, data, callback) {
  if(typeof tabid === 'undefined')
    return;
  chrome.tabs.get(tabid, function(tab) {
    if(typeof tab === 'undefined' || !tab) {
      log("tab " + tabid + " is already closed.")
      callback(null);
      return;
    }
    else {
      chrome.tabs.executeScript(tabid, data, callback);
    }
  });
}


function addHover(tabid) {
  chrome.tabs.sendRequest(tabid,{type:"show_hover"}, function(response) {
    log("Hover shown");
  });

}

function addHoverToTab(tab) {
  addHover(tab.id);
}

chrome.pageAction.onClicked.addListener(addHoverToTab);

function isAlreadyKept(bms, location) {
  try {
    var found;
    $.each(bms, function(index, bm) {
      if (bm.url) {
        found = found || URI(location).equals(URI(bm.url));
      }
      else {
        found = found || ( bm.children && isAlreadyKept(bm.children, location));
      }
      return !found
    });
    return found;
  } catch (e) {
    error(e);
  }
}

function remoteIsAlreadyKept(location, callback) {
  log("checking if user has already bookmarked page: " + location)
  var userConfig = getConfigs();
  if(!userConfig || !userConfig.user || !userConfig.user["keepit_external_id"]) {
    log("Can't check if already kept, no user info!");
    return;
  }

  $.get("http://" + getConfigs().server + "/bookmarks/check?externalId=" + userConfig.user["keepit_external_id"] + "&uri=" + encodeURIComponent(location), null,
    function(data) {
      callback(data["user_has_bookmark"]) 
    },
    "json"
  ).error(function(error) {
    log(error.responseText);
    callback(false);
  });
}

function setPageIcon(tabId, is_kept) {
  try {
    if(typeof tabId === 'undefined') {
      return;
    }
    chrome.tabs.get(tabId, function(tab) {
      if(typeof tab === 'undefined' || !tab)
        return;
      if(is_kept === true) {
        chrome.pageAction.setIcon({"tabId":tabId, "path":"kept.png"});
      }
      else {
        chrome.pageAction.setIcon({"tabId":tabId, "path":"keepit.png"});
      }
      showPageIcon(tabId);
    });
  }  catch (e) { }
}

function showPageIcon(tabId) {
  chrome.tabs.get(tabId, function(tab) {
    if (chrome.extension.lastError) {
      //Possibly do some tidy-up so you don't try again
      return;
    }
    if(typeof tab === 'undefined' || !tab) {
      return;
    }
    else {
      chrome.pageAction.show(tabId);
    }
  });
}

chrome.tabs.onActivated.addListener(function(activeInfo) {
  console.log("Active");
  chrome.tabs.query({active: true}, function(tabs) {
    var tab = tabs[0];
    if(tab.url.indexOf("http") === 0)
      chrome.pageAction.show(tab.id);
  });
});

chrome.tabs.onUpdated.addListener(function(tabId, change) {
  log("Updated");
  chrome.tabs.query({active: true}, function(tabs) {
    log("Two")
    var tab = tabs[0];
    if(tab.url.indexOf("http") === 0)
      chrome.pageAction.show(tab.id);
  });
});

function injectGoogleDiv(tab) {
  log("injecting google_inject.js into tab", tab)
  try {
    safeExecuteScript(tab.id, {code:"console.log('[" + new Date().getTime() + "] (from injector) [keepit:google] tab: " + tab.id + "')"}, 
      function(vars){
        log("[callback] executed logging", tab);
    });
    safeExecuteScript(tab.id, {file:"google_inject.js"}, function(vars){
      log("[callback] executed google_inject.js", tab);
    });
    log("injected google_inject.js into tab", tab)
  } catch (e) {
    error(e);
  }
}

// Listen for the content script to send a message to the background page.
chrome.extension.onRequest.addListener(onRequest);

function getFullyQualifiedKey(key) {
  var env = localStorage["env"];
  if (!env) {
    env = "production";
  }
  var fullyQualifiedKey = env + "_" + key;
  //log("fullyQualifiedKey for key is " + fullyQualifiedKey);
  return fullyQualifiedKey;
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
      env = "production";
      localStorage["env"]=env;
    }
    var maxResStr = localStorage[getFullyQualifiedKey("max_res")];
    if (!isNumber(maxResStr)) {
      maxResStr = 5;
    }
    var maxRes = Number(maxResStr);

    var showScore = localStorage[getFullyQualifiedKey("show_score")];
    if (showScore === "yes" || showScore === true || showScore === "true") {
      showScore = true;
    } else {
      showScore = false;
    }

    var uploadOnStart = localStorage[getFullyQualifiedKey("upload_on_start")];
    if (uploadOnStart === "yes" || uploadOnStart === true || uploadOnStart === "true") {
      uploadOnStart = true;
    } else {
      uploadOnStart = false;
    }

    var hoverTimeout = localStorage[getFullyQualifiedKey("hover_timeout")];
    if (typeof hoverTimeout === 'undefined' || hoverTimeout === null) {
      hoverTimeout = 10;
    }
    if (!isNumber(hoverTimeout)) {
      throw Error("hover timeout is not a number: " + hoverTimeout);
    }
    hoverTimeout = Number(hoverTimeout);

    var server = "keepitfindit.com";
    if (env === "development") {
      server = "dev.ezkeep.com:9000";
    }
    //debugger;
    var userInfo = localStorage[getFullyQualifiedKey("user")];
    var userInfoString;

    try {
      if (userInfo && userInfo != 'undefined') {
        userInfoString = JSON.parse(userInfo);
      }
    }
    catch(e) {} // if we can't parse the JSON, just let is stay undefined.
    var config = {
      "env": env, 
      "hover_timeout": hoverTimeout, 
      "show_score": showScore, 
      "upload_on_start": uploadOnStart, 
      "max_res": maxRes,
      "server": server,
      "user": userInfoString
    }
    //log("loaded config:");
    //log(config);
    return config;
  } catch (e) {
    console.log("User config")
    console.log(userInfo);
    error(e);
  }
}

function isNumber(n) {
  return !isNaN(parseFloat(n)) && isFinite(n);
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
  var details = chrome.app.getDetails();
  return details.version;
}


function startHandShake(callback){
  log("starting handShake");
  $.get("http://" + getConfigs().server + "/isLoggedIn", null,
    function(data) {         
      log("got success response "+data);
      log(data)
      callback(data) 
    },
    "json"
  ).error(function(error) {
    log(error.responseText);
    callback(null);
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

function generateBrowserInstanceId() {
  var S4 = function () {
      return Math.floor(Math.random() * 0x10000).toString(16);
  };
  return ( S4()+S4() + "-" + S4() + "-" + S4() + "-" + S4() + "-" + S4()+S4()+S4() );
}

function getBrowserInstanceId() {
  var browserInstanceId = getConfigs().browserInstanceId;
  if(typeof browserInstanceId == 'undefined' || browserInstanceId == '') {
    browserInstanceId = generateBrowserInstanceId();
    setConfigs('browserInstanceId', browserInstanceId);
  }
  return browserInstanceId;
}


var userAgent = navigator.userAgent || navigator.appVersion || navigator.vendor;
var platform = navigator.platform;
var language = navigator.language;


$.get("http://" + getConfigs().server + "/bookmarks/check?externalId=" + userConfig.user["keepit_external_id"] + "&uri=" + encodeURIComponent(location), null,
  function(data) {
    callback(data["user_has_bookmark"]) 
  },
  "json"
).error(function(error) {
  log(error.responseText);
  callback(false);
});

var popup = null;

function openFacebookConnect() {
  chrome.tabs.onUpdated.addListener(function(tabId, changeInfo, tab) {
    if(changeInfo.status == "loading") {
      if (tabId === popup && tab.url === "http://" + getConfigs().server + "/#_=_") {
        popup = undefined;
        startHandShake(function(data) {
          if (data) {
            log("got handshake data ");
            log(data);
            var userInfo = {};
            userInfo.facebook_id = data.facebookId;
            userInfo.keepit_external_id = data.externalId;
            userInfo.avatar_url = data.avatarUrl;
            userInfo.name = data.name;
            log("updating userInfo ");
            log(userInfo);
            setConfigs("user", JSON.stringify(userInfo));
            postBookmarks(chrome.bookmarks.getTree, "INIT_LOAD");//posting bookmarks
            log("handshake done");
          }
          else{
            log("handshake failes");
          }
          chrome.tabs.remove(tabId);
        });
      }
    }
  });

  chrome.windows.create({'url': 'http://' + getConfigs().server + '/authenticate/facebook', 'type': 'popup', 'width' : 1020, 'height' : 530}, function(window) {
    popup = window.tabs[0].id
  });
}

function resetUserObjectIfInDevMode() {
  var config = getConfigs();
  if(config["env"] == "development") {
    log("dev mode detected, removing user " + JSON.stringify(config["user"]));
    removeFromConfigs("user");
    log("user removed");
  }
}

resetUserObjectIfInDevMode();

if (!hasKeepitIdAndFacebookId()) {
  var userInfo = {};
  setConfigs("user", JSON.stringify(userInfo)); // initialzie user info
  log("open facebook connect - till it is closed keepit is (suppose) to be disabled");
  openFacebookConnect();
} else {
  log("find user info in local storage");

  startHandShake(function(data) {
    if(data == null) {
      // Need to refresh Facebook info
      log("User does not appear to be logged in remote. Refreshing data...");
      var userInfo = {};
      setConfigs("user", JSON.stringify(userInfo));
      openFacebookConnect();
    }
    else {
      log("User logged in, loading bookmarks");
      var userConfigs = getConfigs();
      log(userConfigs);
      var config = getConfigs();
      if(config["upload_on_start"] === true) {
        log("loading bookmarks to the server");
        postBookmarks(chrome.bookmarks.getTree, "PLUGIN_START");//posting bookmarks even when keepit id is found
      } else {
        log("NOT loading bookmarks to the server");
      }
      getBookMarks();

      
    }
  });

}

logEvent("Plugin started!");