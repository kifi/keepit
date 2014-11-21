// API for main.js
/*jshint globalstrict:true */
'use strict';

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

var errors = [];
errors.wrap = function (fn) {
  return function wrapped() {
    try {
      return fn.apply(this, arguments)
    } catch (e) {
      errors.push({error: e, params: {arguments: Array.slice(arguments)}})
    }
  };
};

// TODO: load some of these APIs on demand instead of up front
const self = require('sdk/self');
const timers = require('sdk/timers');
const {Ci, Cc, Cu} = require('chrome');
const {deps} = require('./deps');
const {Listeners} = require('./listeners');
const icon = require('./icon');
const windows = require('sdk/windows').browserWindows;
const tabs = require('sdk/tabs');
const workerNs = require('sdk/core/namespace').ns();
const screenshot = require('./screenshot');

const httpRe = /^https?:/;

const pages = {}, tabsById = {};
function createPage(tab) {
  if (!tab || !tab.id) throw Error(tab ? 'tab without id' : 'tab required');
  log('[createPage]', tab.id, tab.url);
  var page = pages[tab.id] = {id: tab.id, url: tab.url};
  workerNs(page).workers = [];
  return page;
}

exports.bookmarks = require("./bookmarks");

exports.errors = {
  init: function (handler) {
    while (errors.length) {
      handler.push(errors.shift());
    }
    handler.wrap = errors.wrap;
    errors = handler;
  },
  push: function (o) {
    errors.push(o);
  },
  wrap: errors.wrap
};

exports.icon = {
  on: {click: new Listeners},
  set: function(page, path) {
    if (page === pages[page.id]) {
      page.icon = path;
      log('[api.icon.set]', page.id, path);
      var tab = tabsById[page.id], win = tab.window;
      if (tab === win.tabs.activeTab) {
        icon.show(win, self.data.url(path));
      }
    }
  }};
var onIconClick = errors.wrap(function onIconClick(win) {
  exports.icon.on.click.dispatch(pages[win.tabs.activeTab.id]);
});

var addon;
Cu.import('resource://gre/modules/AddonManager.jsm');
AddonManager.getAddonByID(self.id, function (a) {
  addon = a;
});
exports.isPackaged = function () {
  return !addon || !!addon.sourceURI;
};

exports.loadReason = {upgrade: 'update', downgrade: 'update'}[self.loadReason] || self.loadReason;

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
var log = exports.log = function log() {
  var d = new Date, ds = d.toString(), t = '[' + ds.substr(0,2) + ds.substr(15,9) + '.' + String(+d).substr(10) + ']';
  for (var args = Array.slice(arguments), i = 0; i < args.length; i++) {
    var arg = args[i];
    if (typeof arg == 'object') {
      try {
        args[i] = JSON.stringify(arg);
      } catch (e) {
        args[i] = String(arg) + '{' + Object.keys(arg).join(',') + '}';
      }
    }
  }
  if (hexRe.test(args[0])) {
    args[0] = t;
  } else {
    args.unshift(t);
  }
  console.log.apply(console, args);
};

// TODO: actually toggle content script logging
exports.toggleLogging = exports.noop = function () {};

exports.on = {
  beforeSearch: new Listeners,
  search: new Listeners
};

var nsISound, nsIIO;
exports.play = function(path) {
  nsISound = nsISound || Cc['@mozilla.org/sound;1'].createInstance(Ci.nsISound);
  nsIIO = nsIIO || Cc['@mozilla.org/network/io-service;1'].getService(Ci.nsIIOService);
  nsISound.play(nsIIO.newURI(self.data.url(path), null, null));
};

var portHandlers, portMessageTypes;
exports.port = {
  on: function (handlers) {
    if (portHandlers) throw Error('api.port.on already called');
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
var onPortMessage = errors.wrap(function onPortMessage(page, type, data, callbackId) {
  log('[worker.port.on] message:', type, 'data:', data, 'callbackId:', callbackId);
  portHandlers[type](data, respondToTab.bind(this, callbackId, page), page);
});
function respondToTab(callbackId, page, response) {
  if (this.handling) {
    if (pages[page.id] === page) {
      this.port.emit('api:respond', callbackId, response);
    } else {
      log('[respondToTab] page hidden', page.id, callbackId);
    }
  }
}

exports.util = {
  btoa: function(str) {
    return require('sdk/base64').encode(str);
  }
};

exports.browser = {
  name: 'Firefox',
  userAgent: Cc['@mozilla.org/network/protocol;1?name=http'].getService(Ci.nsIHttpProtocolHandler).userAgent
};

exports.requestUpdateCheck = function () {
  log('[requestUpdateCheck]');
  if (addon) {
    var appVer = Cc['@mozilla.org/xre/app-info;1'].getService(Ci.nsIXULAppInfo).version;
    var versions = Cc['@mozilla.org/xpcom/version-comparator;1'].getService(Ci.nsIVersionComparator);
    addon.findUpdates({
        onCompatibilityUpdateAvailable: exports.noop,
        onNoCompatibilityUpdateAvailable: exports.noop,
        onUpdateAvailable: function (addon, install) {
          log('[onUpdateAvailable]', self.version, '=>', install.version, install.state);
          if (versions.compare(self.version, install.version) < 0) {
            var listener = {
              onNewInstall: exports.noop,
              onDownloadStarted: exports.noop,
              onDownloadProgress: exports.noop,
              onDownloadEnded: onDownloadEnded,
              onDownloadCancelled: exports.noop,
              onDownloadFailed: exports.noop,
              onInstallStarted: exports.noop,
              onInstallEnded: stopListening,
              onInstallCancelled: stopListening,
              onInstallFailed: stopListening,
              onExternalInstall: exports.noop
            };
            install.addListener(listener);
            install.install();
          }
          function onDownloadEnded(install) {
            log('[onDownloadEnded]', self.version, '=>', install.version, install.state);
            if (versions.compare(self.version, install.version) >= 0) {
              install.cancel();
            }
          }
          function stopListening() {
            install.removeListener(listener);
          }
        },
        onNoUpdateAvailable: function (addon) {
          log('[onNoUpdateAvailable]');
        },
        onUpdateFinished: function (addon, error) {
          log('[onUpdateFinished]', error);
        }
      },
      AddonManager.UPDATE_WHEN_NEW_APP_DETECTED, appVer, appVer);
  }
};

exports.screenshot = function (callback) {
  callback(screenshot.takeToCanvas(), screenshot.createBlankCanvas());
};

const {SocketCommander} = require('./socket_commander');
var socketPage, socketCommanders = {}, nextSocketId = 1;
exports.socket = {
  open: function(url, handlers, onConnect, onDisconnect) {
    var socketId = nextSocketId++;
    log('[api.socket.open]', socketId, url);
    var sc = socketCommanders[socketId] = new SocketCommander({
      send: function (arr) {
        socketPage.port.emit('socket_send', socketId, arr);
      },
      close: function() {
        socketPage.port.emit('close_socket', socketId);
        delete socketCommanders[socketId];
        if (isEmptyObj(socketCommanders)) {
          socketPage.destroy();
          socketPage = null;
        }
      }
    }, handlers, onConnect, onDisconnect, log);
    if (socketPage) {
      socketPage.port.emit('open_socket', socketId, url);
    } else {
      socketPage = require('sdk/page-worker').Page({
        contentScriptFile: [
          self.data.url('scripts/lib/rwsocket.js'),
          self.data.url('scripts/workers/socket_worker.js')],
        contentScriptWhen: 'start',
        contentScriptOptions: {socketId: socketId, url: url},
        contentURL: self.data.url('html/workers/socket_worker.html')
      });
      socketPage.port.on('socket_connect', onSocketConnect);
      socketPage.port.on('socket_disconnect', onSocketDisconnect);
      socketPage.port.on('socket_message', onSocketMessage);
    }
    return sc;
  }
}
var onSocketConnect = errors.wrap(function onSocketConnect(socketId) {
  var sc = socketCommanders[socketId];
  if (sc) {
    sc.onConnect();
  } else {
    log('[onSocketConnect] no SocketCommander', socketId);
  }
});
var onSocketDisconnect = errors.wrap(function onSocketDisconnect(socketId, why, sec) {
  var sc = socketCommanders[socketId];
  if (sc) {
    sc.onDisconnect(why, sec);
  } else {
    log('[onSocketDisconnect] no SocketCommander', socketId, why, sec);
  }
});
var onSocketMessage = errors.wrap(function onSocketMessage(socketId, data) {
  var sc = socketCommanders[socketId];
  if (sc) {
    sc.onMessage(data);
  } else {
    log('[onSocketMessage] no SocketCommander', socketId, data);
  }
});
function isEmptyObj(o) {
  for (var p in o) {
    if (o.hasOwnProperty(p)) {
      return false;
    }
  }
  return true;
}

exports.storage = require('sdk/simple-storage').storage;

exports.tabs = {
  anyAt: function(url) {
    for each (let page in pages) {
      if (page.url === url) {
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
      params.onOpen = errors.wrap(function (tab) {
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
  close: function(tabId) {
    var tab = tabsById[tabId];
    if (tab) {
      tab.close();
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
            log('[api.tabs.emit]', tab.id, 'type:', type, 'data:', data, 'url:', tab.url);
          }
        }
      }
    }
    if (!emitted) {
      if (page && opts && opts.queue) {
        var toEmit = page.toEmit;
        if (toEmit) {
          if (opts.queue === 1) {
            for (var i = 0; i < toEmit.length; i++) {
              var m = toEmit[i];
              if (m[0] === type) {
                m[1] = data;
                return;
              }
            }
          }
          toEmit.push([type, data]);
        } else {
          page.toEmit = [[type, data]];
        }
      } else {
        log('[api.tabs.emit]', tab.id, 'type:', type, 'neither emitted nor queued for:', tab.url);
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
  }
};

exports.timers = {
  setTimeout: function (f, ms) {
    return timers.setTimeout(errors.wrap(f), ms);
  },
  setInterval: function (f, ms) {
    return timers.setInterval(errors.wrap(f), ms);
  },
  clearTimeout: timers.clearTimeout.bind(timers),
  clearInterval: timers.clearInterval.bind(timers)
};
exports.version = self.version;
exports.xhr = require('sdk/net/xhr').XMLHttpRequest;

// initializing tabs and pages

tabs
.on('open', errors.wrap(function onTabOpen(tab) {
  log('[tabs.open]', tab.id, tab.url);
  tabsById[tab.id] = tab;
}))
.on('close', errors.wrap(function onTabClose(tab) {
  log('[tabs.close]', tab.id, tab.url);
  onPageHide(tab.id);
  delete tabsById[tab.id];
}))
.on('activate', errors.wrap(function onTabActivate(tab) {
  var page = pages[tab.id];
  if (!page || !page.active) {
    log('[tabs.activate]', tab.id, tab.url);
    if (!/^about:/.test(tab.url)) {
      if (page) {
        if (page.icon) {
          icon.show(tab.window, self.data.url(page.icon));
        } else {
          icon.hide(tab.window);
        }
      } else {
        page = createPage(tab);
      }
      if (tab.window === windows.activeWindow && httpRe.test(page.url)) {
        exports.tabs.on.focus.dispatch(page);
      }
    }
  }
}))
.on('deactivate', errors.wrap(function onTabDeactivate(tab) {  // note: can fire after "close"
  log('[tabs.deactivate]', tab.id, tab.url);
  if (tab.window === windows.activeWindow) {
    var page = pages[tab.id];
    if (page && httpRe.test(page.url)) {
      exports.tabs.on.blur.dispatch(page);
    }
  }
}))
.on('ready', errors.wrap(function onTabReady(tab) {
  log('[tabs.ready]', tab.id, tab.url);
}));

var activeWinHasFocus = true;
windows
.on('open', errors.wrap(function onWindowOpen(win) {
  log('[windows.open]', win.title);
  win.removeIcon = icon.addToWindow(win, onIconClick);
}))
.on('close', errors.wrap(function onWindowClose(win) {
  log('[windows.close]', win.title);
  removeFromWindow(win);
}))
.on('activate', errors.wrap(function onWindowActivate(win) {
  activeWinHasFocus = true;
  var tab = win.tabs.activeTab, tabId, page;
  try { // bugzil.la/992509
    tabId = tab.id;
  } catch (e) {
    log('[windows:activate]', e);
  }
  if (tabId && (page = pages[tabId]) && httpRe.test(page.url)) {
    exports.tabs.on.focus.dispatch(page);
  }
}))
.on('deactivate', errors.wrap(function onWindowDeactivate(win) {
  activeWinHasFocus = false;
  var tab = win.tabs.activeTab, tabId, page;
  try { // bugzil.la/992509
    tabId = tab.id;
  } catch (e) {
    log('[windows:deactivate]', e);
  }
  if (tabId && (page = pages[tabId]) && httpRe.test(page.url)) {
    exports.tabs.on.blur.dispatch(page);
  }
}));

errors.wrap(function initTabsAndPages() {
  for each (let win in windows) {
    if (!win.removeIcon) {
      log('[windows] adding icon to window:', win.title);
      win.removeIcon = icon.addToWindow(win, onIconClick);
    }
    for each (let tab in win.tabs) {
      log('[windows]', tab.id, tab.url);
      if (tab.id) {
        tabsById[tab.id] = tab;
        pages[tab.id] || createPage(tab);
      } else {
        errors.push({error: Error('Firefox tab has no ID'), tab: tab, win: win});
      }
    }
  }
})();

// before search

require('./location').onFocus(errors.wrap(function (whence) {
  exports.on.beforeSearch.dispatch(whence);
}));

// navigation handling

const stripHashRe = /^[^#]*/;
const googleSearchRe = /^https?:\/\/www\.google\.[a-z]{2,3}(?:\.[a-z]{2})?\/(?:|search|webhp)[\?#](?:.*&)?q=([^&#]*)(?:.*&channel=(\w+))?/;
const plusRe = /\+/g;

require('./location').onChange(errors.wrap(function onLocationChange(tabId, newPage) { // called before onAttach for all pages except images
  const tab = tabsById[tabId];
  log('[location:change]', tabId, 'newPage:', newPage, tab.url);
  if (newPage) {
    let page = getPageOrHideOldAndCreatePage(tab);
    let match = googleSearchRe.exec(tab.url);
    if (match) {
      let query;
      try {
        query = decodeURIComponent(match[1].replace(plusRe, ' ')).trim();
      } catch (e) {
        log('[location:change] non-UTF-8 search query:', match[1], e);  // e.g. www.google.co.il/search?hl=iw&q=%EE%E9%E4
      }
      if (query) {
        let channel = match[2];
        exports.on.search.dispatch(query, channel === 'fflb' ? 'a' : channel === 'sb' ? 's' : 'n');
      }
    }
    if (httpRe.test(page.url)) {
      exports.tabs.on.loading.dispatch(page);
    }
  } else {
    let page = pages[tabId];
    if (page && page.url !== tab.url) {
      if (httpRe.test(page.url) && page.url.match(stripHashRe)[0] != tab.url.match(stripHashRe)[0]) {
        exports.tabs.on.unload.dispatch(page, true);
        page.url = tab.url;
        page.usedHistoryApi = true;
        exports.tabs.on.loading.dispatch(page);
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
      exports.tabs.on.unload.dispatch(page);
    }
  }
}

// attaching content scripts

var workerOnPageShow = errors.wrap(function workerOnPageShow(tab, page, worker) {
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

var workerOnPageHide = errors.wrap(function workerOnPageHide(tabId) {
  log('[pagehide] tab:', tabId);
  onPageHide(tabId);
});

var workerOnApiHandling = errors.wrap(function workerOnApiHandling(page, worker, types) {
  log('[api:handling]', types);
  for each (let type in types) {
    worker.handling[type] = true;
  }
  emitQueuedMessages(page, worker);
});

var workerOnApiRequire = errors.wrap(function workerOnApiRequire(page, worker, injectedJs, paths, callbackId) {
  if (pages[page.id] === page) {
    var o = deps(paths, merge(merge({}, page.injectedCss), injectedJs));
    log('[api:require]', page.id, o);
    mergeArr(page.injectedCss, o.styles);
    mergeArr(injectedJs, o.scripts);
    worker.port.emit('api:inject', o.styles, o.scripts.map(self.data.load), callbackId);
  } else {
    log('[api:require] page hidden', page.id, o);
  }
});

const {PageMod} = require('sdk/page-mod');
require('./meta').contentScripts.forEach(function (arr) {
  const path = arr[0], urlRe = arr[1], o = deps(path);
  log('defining PageMod:', path, 'deps:', o);
  PageMod({
    include: urlRe,
    contentStyleFile: o.styles.map(self.data.url),
    contentScriptFile: o.scripts.map(self.data.url),
    contentScriptWhen: arr[2] ? 'start' : 'ready',
    contentScriptOptions: {dataUriPrefix: self.data.url(''), dev: exports.mode.isDev(), version: self.version},
    attachTo: ['existing', 'top'],
    onAttach: errors.wrap(function onAttachPageMod(worker) { // called before location:change for pages that are images
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

function emitQueuedMessages(page, worker) {
  var toEmit = page.toEmit;
  if (toEmit) {
    for (var i = 0; i < toEmit.length;) {
      var m = toEmit[i];
      if (worker.handling[m[0]]) {
        log('[emitQueuedMessages]', page.id, m[0], m[1] != null ? m[1] : '');
        worker.port.emit.apply(worker.port, m);
        toEmit.splice(i, 1);
      } else {
        i++;
      }
    }
    if (!toEmit.length) {
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
