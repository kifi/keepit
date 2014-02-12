// API for main.js
/*jshint globalstrict:true */
"use strict";

function dispatch() {
  var args = arguments;
  this.forEach(function(f) {f.apply(null, args)});
}

function merge(o1, o2) {
  for (let k in o2) {
    o1[k] = o2[k];
  }
  return o1;
}

function mergeArr(o, arr) {
  for (let k of arr) {
    o[k] = true;
  }
  return o;
}

// TODO: load some of these APIs on demand instead of up front
const self = require('sdk/self'), data = self.data, load = data.load.bind(data), url = data.url.bind(url);
const timers = require("sdk/timers");
const {Ci, Cc, Cu} = require('chrome');
const {deps} = require("./deps");
const {Airbrake} = require('./airbrake.min');
const {Listeners} = require("./listeners");
const icon = require('./icon');
const windows = require("sdk/windows").browserWindows;
const tabs = require('sdk/tabs');
const workerNs = require('sdk/core/namespace').ns();

const httpRe = /^https?:/;

const pages = {}, tabsById = {};
function createPage(tab) {
  if (!tab || !tab.id) throw Error(tab ? "tab without id" : "tab required");
  log('[createPage]', tab.id, tab.url);
  var page = pages[tab.id] = {id: tab.id, url: tab.url};
  workerNs(page).workers = [];
  return page;
}

exports.bookmarks = require("./bookmarks");

exports.icon = {
  on: {click: new Listeners},
  set: function(page, path) {
    if (page === pages[page.id]) {
      page.icon = path;
      log("[api.icon.set]", page.id, path);
      var tab = tabsById[page.id], win = tab.window;
      if (tab === win.tabs.activeTab) {
        icon.show(win, url(path));
      }
    }
  }};
var onIconClick = Airbrake.wrap(function onIconClick(win) {
  dispatch.call(exports.icon.on.click, pages[win.tabs.activeTab.id]);
});

var isPackaged = true;
exports.isPackaged = function () {
  return isPackaged;
};
Cu.import('resource://gre/modules/AddonManager.jsm');
AddonManager.getAddonByID(self.id, Airbrake.wrap(function (addon) {
  isPackaged = !!addon.sourceURI;
}));

exports.loadReason = {upgrade: "update", downgrade: "update"}[self.loadReason] || self.loadReason;

const prefs = require('sdk/simple-prefs');
exports.mode = {
  isDev: function () {
    return prefs.mode === 'dev';
  },
  toggle: function () {
    if (prefs.mode) {
      delete prefs.mode;
    } else {
      prefs.mode = 'dev';
    }
    // TODO: reload extension
  }
};

const hexRe = /^#[0-9a-f]{3}$/i;
exports.log = function() {
  var d = new Date, ds = d.toString(), t = "[" + ds.substr(0,2) + ds.substr(15,9) + "." + String(+d).substr(10) + "]";
  for (var args = Array.slice(arguments), i = 0; i < args.length; i++) {
    var arg = args[i];
    if (typeof arg == "object") {
      try {
        args[i] = JSON.stringify(arg);
      } catch (e) {
        args[i] = String(arg) + "{" + Object.keys(arg).join(",") + "}";
      }
    }
  }
  if (hexRe.test(args[0])) {
    args[0] = t;
  } else {
    args.unshift(t);
  }
  return console.log.apply.bind(console.log, console, args);
};
function log() {
  exports.log.apply(null, arguments)();
}
exports.log.error = function(exception, context) {
  console.error((context ? "[" + context + "] " : "") + exception);
  console.error(exception.stack);
};

// TODO: actually toggle content script logging
exports.toggleLogging = exports.noop = function () {};

exports.on = {
  beforeSearch: new Listeners,
  search: new Listeners
};

var nsISound, nsIIO;
exports.play = function(path) {
  nsISound = nsISound || Cc["@mozilla.org/sound;1"].createInstance(Ci.nsISound);
  nsIIO = nsIIO || Cc["@mozilla.org/network/io-service;1"].getService(Ci.nsIIOService);
  nsISound.play(nsIIO.newURI(url(path), null, null));
};

exports.popup = {
  open: function(options, handlers) {
    var onReady = Airbrake.wrap(function onReady(tab) {
      handlers.navigate.call(tab, tab.url);
    });
    var win = windows.open({
      url: options.url,
      onOpen: Airbrake.wrap(function() {
        if (handlers && handlers.navigate) {
          win.tabs.activeTab.on("ready", onReady);
        }
      }),
      onClose: Airbrake.wrap(function() {
        win.tabs.activeTab.removeListener("ready", onReady);
      })
    });

    // Below are some failed attempts at opening a popup window...
    // UPDATE: see https://addons.mozilla.org/en-US/developers/docs/sdk/1.12/modules/sdk/frame/utils.html

    // win.window, win.document, win.getInterface
    // var win = require("api-utils/window/utils").open(options.url, {
    //   name: options.name,
    //   features: {
    //     centerscreen: true,
    //     width: options.width || undefined,
    //     height: options.height || undefined}});
    // var { Cc, Ci } = require('chrome')
    // var ww = Cc["@mozilla.org/embedcomp/window-watcher;1"].getService(Ci.nsIWindowWatcher);
    // log("=========== OPENED:", typeof ww.getChromeForWindow(win));
    // timers.setTimeout(function() {
    //   win.close();
    // }, 50000);

    // ww.registerNotification({
    //   observe: function(aSubject, aTopic, aData) {
    //     log("============== OBSERVED!", aSubject, aTopic, aData);
    //   }});

    // WORKS! win.getInterface(Ci.nsIWebNavigation);
    // FAILS! win.getInterface(Ci.nsIWebBrowser);
    // FAILS! win.getInterface(Ci.nsIWebProgress);
    // FAILS! win.getXULWindow()
  }};

var portHandlers, portMessageTypes;
exports.port = {
  on: function (handlers) {
    if (portHandlers) throw Error("api.port.on already called");
    portHandlers = handlers;
    portMessageTypes = Object.keys(handlers);
    for each (let page in pages) {
      for (let worker of workerNs(page).workers) {
        bindPortHandlers(worker, page);
      }
    }
  }};
function bindPortHandlers(worker, page) {
  portMessageTypes.forEach(function (type) {
    worker.port.on(type, onPortMessage.bind(worker, page, type));
  });
}
var onPortMessage = Airbrake.wrap(function onPortMessage(page, type, data, callbackId) {
  log('[worker.port.on] message:', type, 'data:', data, 'callbackId:', callbackId);
  portHandlers[type](data, this.port.emit.bind(this.port, 'api:respond', callbackId), page);
});

exports.request = function(method, url, data, done, fail) {
  var options = {
    url: url,
    onComplete: Airbrake.wrap(function (resp) {
      var keys = [];
      for (var key in resp) {
        keys.push(key);
      }
      if (resp.status >= 200 && resp.status < 300) {
        done && done(resp.json || resp);
      } else if (fail) {
        fail(resp);
      }
      done = fail = null;
    })
  };
  if (data != null && data !== '') {
    options.contentType = "application/json; charset=utf-8";
    options.content = typeof data === 'string' ? data : JSON.stringify(data);
  }
  require('sdk/request').Request(options)[method.toLowerCase()]();
};

exports.postRawAsForm = function(url, data) {
  var options = {
    url: url,
    contentType: 'application/x-www-form-urlencoded',
    content: data
  }
  require('sdk/request').Request(options).post();
};

exports.util = {
  btoa: function(str) {
    return require('sdk/base64').encode(str);
  }
};

exports.browser = {
  name: 'Firefox',
  userAgent: Cc['@mozilla.org/network/protocol;1?name=http'].getService(Ci.nsIHttpProtocolHandler).userAgent
};

exports.requestUpdateCheck = exports.log.bind(null, '[requestUpdateCheck] unsupported');

var socketPage, sockets = [,];
var socketCallbacks = {}, nextSocketCallbackId = 1;  // TODO: garbage collect old uncalled callbacks
exports.socket = {
  open: function(url, handlers, onConnect, onDisconnect) {
    var socketId = sockets.length, socket = {
      seq: 0,
      send: function(arr, callback) {
        if (callback) {
          var id = nextSocketCallbackId++;
          socketCallbacks[id] = callback;
          arr.splice(1, 0, id);
        }
        socketPage.port.emit('socket_send', socketId, arr);
      },
      close: function() {
        log('[api.socket.close]', socketId);
        delete sockets[socketId];
        socketPage.port.emit('close_socket', socketId);
        if (!sockets.some(function(h) {return h})) {
          socketPage.destroy();
          socketPage = null;
        }
        this.send = this.close = exports.noop;
      }};
    log('[api.socket.open]', socketId, url);
    sockets.push({socket: socket, handlers: handlers, onConnect: onConnect, onDisconnect: onDisconnect});
    if (socketPage) {
      socketPage.port.emit('open_socket', socketId, url);
    } else {
      socketPage = require('sdk/page-worker').Page({
        contentScriptFile: [
          data.url('scripts/lib/rwsocket.js'),
          data.url("scripts/workers/socket.js")],
        contentScriptWhen: 'start',
        contentScriptOptions: {socketId: socketId, url: url},
        contentURL: data.url('html/workers/socket.html')
      });
      socketPage.port.on('socket_connect', onSocketConnect);
      socketPage.port.on('socket_disconnect', onSocketDisconnect);
      socketPage.port.on('socket_message', onSocketMessage);
    }
    return socket;
  }
}
var onSocketConnect = Airbrake.wrap(function onSocketConnect(socketId) {
  var socket = sockets[socketId];
  if (socket) {
    socket.socket.seq++;
    try {
      socket.onConnect();
    } catch (e) {
      exports.log.error(e, "onSocketConnect:" + socketId);
    }
  } else {
    log("[onSocketConnect] Ignoring, no socket", socketId);
  }
});
var onSocketDisconnect = Airbrake.wrap(function onSocketDisconnect(socketId, why) {
  var socket = sockets[socketId];
  if (socket) {
    try {
      socket.onDisconnect(why);
    } catch (e) {
      exports.log.error(e, "onSocketDisconnect:" + socketId + ": " + why);
    }
  } else {
    log("[onSocketDisconnect] Ignoring, no socket", socketId);
  }
});
var onSocketMessage = Airbrake.wrap(function onSocketMessage(socketId, data) {
  try {
    var msg = JSON.parse(data);
    if (Array.isArray(msg)) {
      var id = msg.shift();
      if (id > 0) {
        var callback = socketCallbacks[id];
        if (callback) {
          delete socketCallbacks[id];
          callback.apply(null, msg);
        } else {
          log("[api.socket.receive] Ignoring, no callback", id, msg);
        }
      } else {
        var socket = sockets[socketId];
        if (socket) {
          var handler = socket.handlers[id];
          if (handler) {
            handler.apply(null, msg);
          } else {
            log("[api.socket.receive] Ignoring, no handler", id, msg);
          }
        } else {
          log("[api.socket.receive] Ignoring, no socket", socketId, id, msg);
        }
      }
    } else {
      log("[api.socket.receive] Ignoring, not array", msg);
    }
  } catch (e) {
    exports.log.error(e, "api.socket.receive:" + socketId + ":" + data);
  }
});

exports.storage = require('sdk/simple-storage').storage;

exports.tabs = {
  anyAt: function(url) {
    for each (let page in pages) {
      if (page.url == url) {
        return page;
      }
    }
  },
  select: function(tabId) {
    log('[api.tabs.select]', tabId);
    var tab = tabsById[tabId];
    tab.activate();
    tab.window.activate();
  },
  open: function(url, callback) {
    log('[api.tabs.open]', url);
    var params = {url: url};
    if (callback) {
      params.onOpen = Airbrake.wrap(function (tab) {
        callback && callback(tab.id);
      });
    }
    tabs.open(params);
  },
  selectOrOpen: function(url) {
    var tab = exports.tabs.anyAt(url);
    if (tab) {
      exports.tabs.select(tab.id);
    } else {
      exports.tabs.open(url);
    }
  },
  each: function(callback) {
    for each (let page in pages) {
      if (httpRe.test(page.url)) callback(page);
    }
  },
  eachSelected: function(callback) {
    for each (let win in windows) {
      var page = pages[win.tabs.activeTab.id];
      if (page && httpRe.test(page.url)) callback(page);
    }
  },
  emit: function(tab, type, data, opts) {
    var emitted;
    const page = pages[tab.id];
    if (page === tab) {
      for (let worker of workerNs(page).workers) {
        if (worker.handling && worker.handling[type]) {
          worker.port.emit(type, data);
          if (!emitted) {
            emitted = true;
            log("[api.tabs.emit]", tab.id, "type:", type, "data:", data, "url:", tab.url);
          }
        }
      }
    }
    if (!emitted) {
      if (opts && opts.queue) {
        if (page.toEmit) {
          if (opts.queue === 1) {
            for (var i = 0; i < page.toEmit.length; i++) {
              if (page.toEmit[i][0] === type) {
                page.toEmit[i][1] = data;
                return;
              }
            }
          }
          page.toEmit.push([type, data]);
        } else {
          page.toEmit = [[type, data]];
        }
      } else {
        log("[api.tabs.emit]", tab.id, "type:", type, "neither emitted nor queued for:", tab.url);
      }
    }
  },
  get: function(pageId) {
    return pages[pageId];
  },
  getFocused: function () {
    var tab;
    return activeWinHasFocus && (tab = tabs.activeTab) ? pages[tab.id] : null;
  },
  isFocused: function(page) {
    var tab;
    return activeWinHasFocus && (tab = tabs.activeTab) ? tabsById[page.id] === tab : false;
  },
  navigate: function(tabId, url) {
    var tab = tabsById[tabId];
    if (tab) {
      tab.url = url;
      tab.activate();
      tab.window.activate();
    }
  },
  on: {
    focus: new Listeners,
    blur: new Listeners,
    loading: new Listeners,
    unload: new Listeners
  },
  reload: function(tabId) {
    var tab = tabsById[tabId];
    if (tab) {
      tab.reload();
    }
  }};

exports.timers = {
  setTimeout: function (f, ms) {
    timers.setTimeout(Airbrake.wrap(f), ms);
  },
  setInterval: function (f, ms) {
    timers.setInterval(Airbrake.wrap(f), ms);
  },
  clearTimeout: timers.clearTimeout.bind(timers),
  clearInterval: timers.clearInterval.bind(timers)
};
exports.version = self.version;

// initializing tabs and pages

tabs
.on('open', Airbrake.wrap(function onTabOpen(tab) {
  log("[tabs.open]", tab.id, tab.url);
  tabsById[tab.id] = tab;
}))
.on('close', Airbrake.wrap(function onTabClose(tab) {
  log("[tabs.close]", tab.id, tab.url);
  onPageHide(tab.id);
  delete tabsById[tab.id];
}))
.on('activate', Airbrake.wrap(function onTabActivate(tab) {
  var page = pages[tab.id];
  if (!page || !page.active) {
    log("[tabs.activate]", tab.id, tab.url);
    if (!/^about:/.test(tab.url)) {
      if (page) {
        if (page.icon) {
          icon.show(tab.window, url(page.icon));
        } else {
          icon.hide(tab.window);
        }
      } else {
        page = createPage(tab);
      }
      if (tab.window === windows.activeWindow && httpRe.test(page.url)) {
        dispatch.call(exports.tabs.on.focus, page);
      }
    }
  }
}))
.on('deactivate', Airbrake.wrap(function onTabDeactivate(tab) {  // note: can fire after "close"
  log("[tabs.deactivate]", tab.id, tab.url);
  if (tab.window === windows.activeWindow) {
    var page = pages[tab.id];
    if (page && httpRe.test(page.url)) {
      dispatch.call(exports.tabs.on.blur, page);
    }
  }
}))
.on('ready', Airbrake.wrap(function onTabReady(tab) {
  log("[tabs.ready]", tab.id, tab.url);
}));

var activeWinHasFocus = true;
windows
.on('open', Airbrake.wrap(function onWindowOpen(win) {
  log("[windows.open]", win.title);
  win.removeIcon = icon.addToWindow(win, onIconClick);
}))
.on('close', Airbrake.wrap(function onWindowClose(win) {
  log("[windows.close]", win.title);
  removeFromWindow(win);
}))
.on('activate', Airbrake.wrap(function onWindowActivate(win) {
  activeWinHasFocus = true;
  var page = pages[win.tabs.activeTab.id];
  if (page && httpRe.test(page.url)) {
    dispatch.call(exports.tabs.on.focus, page);
  }
}))
.on('deactivate', Airbrake.wrap(function onWindowDeactivate(win) {
  activeWinHasFocus = false;
  var page = pages[win.tabs.activeTab.id];
  if (page && httpRe.test(page.url)) {
    dispatch.call(exports.tabs.on.blur, page);
  }
}));

for each (let win in windows) {
  if (!win.removeIcon) {
    log("[windows] adding icon to window:", win.title);
    win.removeIcon = icon.addToWindow(win, onIconClick);
  }
  for each (let tab in win.tabs) {
    log("[windows]", tab.id, tab.url);
    tabsById[tab.id] = tab;
    pages[tab.id] || createPage(tab);
  }
}

// before search

require('./location').onFocus(Airbrake.wrap(dispatch.bind(exports.on.beforeSearch)));

// navigation handling

const stripHashRe = /^[^#]*/;
const googleSearchRe = /^https?:\/\/www\.google\.[a-z]{2,3}(?:\.[a-z]{2})?\/(?:|search|webhp)\?(?:.*&)?q=([^&#]*)/;
const plusRe = /\+/g;

require('./location').onChange(Airbrake.wrap(function onLocationChange(tabId, newPage) { // called before onAttach for all pages except images
  const tab = tabsById[tabId];
  log('[location:change]', tabId, 'newPage:', newPage, tab.url);
  if (newPage) {
    let page = getPageOrHideOldAndCreatePage(tab);
    let match = googleSearchRe.exec(tab.url);
    if (match) {
      let query = decodeURIComponent(match[1].replace(plusRe, ' ')).trim();
      if (query) dispatch.call(exports.on.search, query);
    }
    if (httpRe.test(page.url)) {
      dispatch.call(exports.tabs.on.loading, page);
    }
  } else {
    let page = pages[tabId];
    if (page.url != tab.url) {
      if (httpRe.test(page.url) && page.url.match(stripHashRe)[0] != tab.url.match(stripHashRe)[0]) {
        dispatch.call(exports.tabs.on.unload, page, true);
        page.url = tab.url;
        dispatch.call(exports.tabs.on.loading, page);
      } else {
        page.url = tab.url;
      }
    }
  }
}));

// This function is needed because location:change and onAttach do not fire in a consistent order.
// location:change fires first for all pages except images.
function getPageOrHideOldAndCreatePage(tab) {
  let page = pages[tab.id];
  if (page && page.url !== tab.url) {
    log('[getPageOrHideOldAndCreatePage]', page.url, '!==', tab.url);
    onPageHide(tab.id);
    page = null;
  }
  // Note: It's possible that this page shouldn't actually be created, since Firefox will grab the actual
  // page from the bfcache. If we detect that in the PageMod pageshow handler below, we discard this page.
  // TODO: Find a way to detect at this point whether the page will be coming from the bfcache and recover
  // our old page object if it is.
  return page || createPage(tab);
}

function onPageHide(tabId) {
  const page = pages[tabId];
  if (page) {
    delete pages[tabId];
    if (httpRe.test(page.url)) {
      dispatch.call(exports.tabs.on.unload, page);
    }
  }
}

// attaching content scripts
  const {PageMod} = require("sdk/page-mod");
  require('./meta').contentScripts.forEach(function (arr) {
    const path = arr[0], urlRe = arr[1], o = deps(path);
    log('defining PageMod:', path, 'deps:', o);
    PageMod({
      include: urlRe,
      contentStyleFile: o.styles.map(url),
      contentScriptFile: o.scripts.map(url),
      contentScriptWhen: arr[2] ? 'start' : 'ready',
      contentScriptOptions: {dataUriPrefix: url(''), dev: exports.mode.isDev(), version: self.version},
      attachTo: ['existing', 'top'],
      onAttach: Airbrake.wrap(function onAttachPageMod(worker) { // called before location:change for pages that are images
        const tab = worker.tab;
        const page = getPageOrHideOldAndCreatePage(tab);

        log('[onAttach]', tab.id, this.contentScriptFile, tab.url, page);
        page.injectedCss = mergeArr({}, o.styles);
        const injectedJs = mergeArr({}, o.scripts);
        workerNs(page).workers.push(worker);
        worker
          .on('pageshow', workerOnPageShow.bind(null, tab, page, worker))  // pageshow/pagehide discussion at bugzil.la/766088#c2
          .on('pagehide', workerOnPageHide.bind(null, tab.id));
        if (portHandlers) {
          bindPortHandlers(worker, page);
        }
        worker.handling = {};
        worker.port
        .on('api:handling', workerOnApiHandling.bind(null, page, worker))
        .on('api:require', workerOnApiRequire.bind(null, page, worker, injectedJs));
      })});
  });

var workerOnPageShow = Airbrake.wrap(function workerOnPageShow(tab, page, worker) {
  if (pages[tab.id] !== page) {  // bfcache used
    log('[api:pageshow] tab:', tab.id, 'updating:', pages[tab.id], '->', page);
    pages[tab.id] = page;
  } else if (page.url !== tab.url) {  // shouldn’t happen
    log('[api:pageshow] tab:', tab.id, 'updating:', page.url, '->', tab.url);
    page.url = tab.url;
  } else {
    log('[api:pageshow] tab:', tab.id, 'url:', tab.url);
  }
  emitQueuedMessages(page, worker);
});

var workerOnPageHide = Airbrake.wrap(function workerOnPageHide(tabId) {
  log('[pagehide] tab:', tabId);
  onPageHide(tabId);
});

var workerOnApiHandling = Airbrake.wrap(function workerOnApiHandling(page, worker, types) {
  log('[api:handling]', types);
  for each (let type in types) {
    worker.handling[type] = true;
  }
  emitQueuedMessages(page, worker);
});

var workerOnApiRequire = Airbrake.wrap(function workerOnApiRequire(page, worker, injectedJs, paths, callbackId) {
  var o = deps(paths, merge(merge({}, page.injectedCss), injectedJs));
  log('[api:require] tab:', page.id, o);
  mergeArr(page.injectedCss, o.styles);
  mergeArr(injectedJs, o.scripts);
  worker.port.emit('api:inject', o.styles.map(load), o.scripts.map(load), callbackId);
});

function emitQueuedMessages(page, worker) {
  if (page.toEmit) {
    for (var i = 0; i < page.toEmit.length;) {
      var m = page.toEmit[i];
      if (worker.handling[m[0]]) {
        log('[emitQueuedMessages]', page.id, m[0], m[1] != null ? m[1] : '');
        worker.port.emit.apply(worker.port, m);
        page.toEmit.splice(i, 1);
      } else {
        i++;
      }
    }
    if (!page.toEmit.length) {
      delete page.toEmit;
    }
  }
}

function removeFromWindow(win) {
  if (win.removeIcon) {
    win.removeIcon();
    delete win.removeIcon;
  }
}

exports.onUnload = function (reason) {
  for each (let win in windows) {
    removeFromWindow(win);
  }
};

// TODO: remove Feb 20
delete prefs.maxResults;
delete prefs.suppressLog;
delete prefs.showSlider;
delete prefs.showScores;
delete prefs.env;
