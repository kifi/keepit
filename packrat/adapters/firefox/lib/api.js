// API for main.js

function dispatch() {
  var args = arguments;
  this.forEach(function(f) {f.apply(null, args)});
}

function markInjected(inj, o) {
  for each (let arr in o) {
    for each (let path in arr) {
      inj[path] = true;
    }
  }
  return inj;
}

// TODO: load some of these APIs on demand instead of up front
const self = require("sdk/self"), data = self.data, load = data.load.bind(data), url = data.url.bind(url);
const timers = require("sdk/timers");
const { Ci, Cc } = require("chrome");
const WM = Cc["@mozilla.org/appshell/window-mediator;1"].getService(Ci.nsIWindowMediator);
const {deps} = require("./deps");
const {Listeners} = require("./listeners");
const icon = require("./icon");
const windows = require("sdk/windows").browserWindows;
const tabs = require("sdk/tabs");
const googleSearchPattern = /^https?:\/\/www\.google\.[a-z]{2,3}(\.[a-z]{2})?\/(|search|webhp)\?(|.*&)q=([^&]*)/;

const pages = {}, workers = {}, tabsById = {};  // all by tab.id
function createPage(tab) {
  if (!tab || !tab.id) throw Error(tab ? "tab without id" : "tab required");
  (workers[tab.id] || (workers[tab.id] = [])).length = 0;
  return pages[tab.id] = {id: tab.id, url: tab.url};
}

exports.bookmarks = require("./bookmarks");

exports.icon = {
  on: {click: new Listeners},
  set: function(page, path) {
    if (page === pages[page.id]) {
      page.icon = path;
      exports.log("page:", page);
      var tab = tabsById[page.id], win = tab.window;
      if (tab === win.tabs.activeTab) {
        icon.show(win, url(path));
      }
    }
  }};
function onIconClick(win) {
  dispatch.call(exports.icon.on.click, pages[win.tabs.activeTab.id]);
}

exports.isPackaged = function() {
  return true; // TODO: detect development environment
};

exports.loadReason = {upgrade: "update", downgrade: "update"}[self.loadReason] || self.loadReason;

const hexRe = /^#[0-9a-f]{3}$/i;
exports.log = function() {
  var d = new Date, ds = d.toString(), args = Array.slice(arguments);
  if (hexRe.test(args[0])) {
    args.shift();
  }
  for (var i = 0; i < args.length; i++) {
    var arg = args[i];
    if (typeof arg == "object") {
      try {
        args[i] = JSON.stringify(arg);
      } catch (e) {
        args[i] = String(arg) + "{" + Object.keys(arg).join(",") + "}";
      }
    }
  }
  console.log("[" + ds.substr(0,2) + ds.substr(15,9) + "." + String(+d).substr(10) + "]", args.join(" "));
};
exports.log.error = function(exception, context) {
  console.error((context ? "[" + context + "] " : "") + exception);
  console.error(exception.stack);
};

exports.noop = function() {};

exports.on = {
  search: new Listeners,
  install: new Listeners,
  update: new Listeners,
  startup: new Listeners};

// Call handlers for load reason async (after main.js has finished).
timers.setTimeout(dispatch.bind(exports.on[exports.loadReason] || []), 0);

var nsISound, nsIIO;
exports.play = function(path) {
  nsISound = nsISound || Cc["@mozilla.org/sound;1"].createInstance(Ci.nsISound);
  nsIIO = nsIIO || Cc["@mozilla.org/network/io-service;1"].getService(Ci.nsIIOService);
  nsISound.play(nsIIO.newURI(url(path), null, null));
};

exports.popup = {
  open: function(options, handlers) {
    var win = windows.open({
      url: options.url,
      onOpen: function() {
        if (handlers && handlers.navigate) {
          win.tabs.activeTab.on("ready", onReady);
        }
      },
      onClose: function() {
        win.tabs.activeTab.removeListener("ready", onReady);
      }});
    function onReady(tab) {
      handlers.navigate.call(tab, tab.url);
    }

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
    // exports.log("=========== OPENED:", typeof ww.getChromeForWindow(win));
    // timers.setTimeout(function() {
    //   win.close();
    // }, 50000);

    // ww.registerNotification({
    //   observe: function(aSubject, aTopic, aData) {
    //     exports.log("============== OBSERVED!", aSubject, aTopic, aData);
    //   }});

    // WORKS! win.getInterface(Ci.nsIWebNavigation);
    // FAILS! win.getInterface(Ci.nsIWebBrowser);
    // FAILS! win.getInterface(Ci.nsIWebProgress);
    // FAILS! win.getXULWindow()
  }};

var portHandlers;
exports.port = {
  on: function(handlers) {
    if (portHandlers) throw Error("api.port.on already called");
    portHandlers = handlers;
  }};

const {prefs} = require("sdk/simple-prefs");
exports.prefs = {
  get: function get(key) {
    if (arguments.length > 1) {
      for (var o = {}, i = 0; i < arguments.length; i++) {
        key = arguments[i];
        o[key] = get(key);
      }
      return o;
    }
    return prefs[key];
  },
  set: function set(key, value) {
    if (typeof key === "object") {
      Object.keys(key).forEach(function(k) {
        set(k, key[k]);
      });
    } else if (value == null) {
      delete prefs[key];
    } else {
      prefs[key] = value;
    }
  }};

exports.request = function(method, url, data, done, fail) {
  var options = {
    url: url,
    onComplete: function(resp) {
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
    }
  };
  if (data) {
    options.contentType = "application/json; charset=utf-8";
    options.content = JSON.stringify(data);
  }
  require("sdk/request").Request(options)[method.toLowerCase()]();
};

exports.requestUpdateCheck = exports.log.bind(null, "[requestUpdateCheck] unsupported");

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
        socketPage.port.emit("socket_send", socketId, arr);
      },
      close: function() {
        exports.log("[api.socket.close]", socketId);
        delete sockets[socketId];
        socketPage.port.emit("close_socket", socketId);
        if (!sockets.some(function(h) {return h})) {
          socketPage.destroy();
          socketPage = null;
        }
        this.send = this.close = exports.noop;
      }};
    exports.log("[api.socket.open]", socketId, url);
    sockets.push({socket: socket, handlers: handlers, onConnect: onConnect, onDisconnect: onDisconnect});
    if (socketPage) {
      socketPage.port.emit("open_socket", socketId, url);
    } else {
      socketPage = require("sdk/page-worker").Page({
        contentScriptFile: [
          data.url("scripts/lib/reconnecting-websocket.js"),
          data.url("scripts/workers/socket.js")],
        contentScriptWhen: "start",
        contentScriptOptions: {socketId: socketId, url: url},
        contentURL: data.url("html/workers/socket.html")
      });
      socketPage.port.on("socket_connect", onSocketConnect);
      socketPage.port.on("socket_disconnect", onSocketDisconnect);
      socketPage.port.on("socket_message", onSocketMessage);
    }
    return socket;
  }
}
function onSocketConnect(socketId) {
  var socket = sockets[socketId];
  if (socket) {
    socket.socket.seq++;
    try {
      socket.onConnect();
    } catch (e) {
      exports.log.error("onSocketConnect:" + socketId, e);
    }
  } else {
    exports.log("[onSocketConnect] Ignoring, no socket", socketId);
  }
}
function onSocketDisconnect(socketId, why) {
  var socket = sockets[socketId];
  if (socket) {
    try {
      socket.onDisconnect(why);
    } catch (e) {
      exports.log.error("onSocketDisconnect:" + socketId + ": " + why, e);
    }
  } else {
    exports.log("[onSocketDisconnect] Ignoring, no socket", socketId);
  }
}
function onSocketMessage(socketId, data) {
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
          exports.log("[api.socket.receive] Ignoring, no callback", id, msg);
        }
      } else {
        var socket = sockets[socketId];
        if (socket) {
          var handler = socket.handlers[id];
          if (handler) {
            handler.apply(null, msg);
          } else {
            exports.log("[api.socket.receive] Ignoring, no handler", id, msg);
          }
        } else {
          exports.log("[api.socket.receive] Ignoring, no socket", socketId, id, msg);
        }
      }
    } else {
      exports.log("[api.socket.receive] Ignoring, not array", msg);
    }
  } catch (e) {
    exports.log.error("api.socket.receive:" + socketId + ":" + data, e);
  }
}

exports.storage = require("sdk/simple-storage").storage;

const hostRe = /^https?:\/\/[^\/]*/;
exports.tabs = {
  anyAt: function(url) {
    for each (let page in pages) {
      if (page.url == url) {
        return page;
      }
    }
  },
  select: function(tabId) {
    exports.log("[api.tabs.select]", tabId);
    tabsById[tabId].activate();
  },
  open: function(url, callback) {
    exports.log("[api.tabs.open]", url);
    tabs.open({
      url: url,
      onOpen: function(tab) {
        callback && callback(tab.id);
      }
    });
  },
  each: function(callback) {
    for each (let page in pages) {
      if (/^https?:/.test(page.url)) callback(page);
    }
  },
  eachSelected: function(callback) {
    for each (let win in windows) {
      var page = pages[win.tabs.activeTab.id];
      if (page && /^https?:/.test(page.url)) callback(page);
    }
  },
  emit: function(tab, type, data) {
    var currTab = pages[tab.id];
    if (tab === currTab || currTab && currTab.url.match(hostRe)[0] == tab.url.match(hostRe)[0]) {
      if (currTab.ready) {
        exports.log("[api.tabs.emit] tab:", tab.id, "type:", type, "data:", data, "url:", tab.url);
        workers[tab.id].forEach(function(worker) {
          worker.port.emit(type, data);
        });
      } else {
        (currTab.toEmit || (currTab.toEmit = [])).push([type, data]);
      }
    } else {
      exports.log("[api.tabs.emit] SUPPRESSED tab:", tab.id, "type:", type, "navigated:", tab.url, "->", currTab && currTab.url);
    }
  },
  get: function(pageId) {
    return pages[pageId];
  },
  isFocused: function(page) {
    var tab = tabsById[page.id], win = tab.window;
    return win === windows.activeWindow && tab === win.tabs.activeTab;
  },
  navigate: function(tabId, url) {
    var tab = tabsById[tabId], win;
    if (tab) {
      tab.url = url;
      win = tab.window;
      if (tab != win.activeTab) tab.activate();
      if (win != windows.activeWindow) win.activate();
    }
  },
  on: {
    focus: new Listeners,
    blur: new Listeners,
    loading: new Listeners,
    ready: new Listeners,
    unload: new Listeners}};

exports.timers = timers;
exports.version = self.version;

// initializing tabs and pages

tabs
.on("open", function(tab) {
  exports.log("[tabs.open]", tab.id, tab.url);
  tabsById[tab.id] = tab;
})
.on("close", function(tab) {
  exports.log("[tabs.close]", tab.id, tab.url);
  var page = pages[tab.id];
  delete pages[tab.id];
  delete workers[tab.id];
  delete tabsById[tab.id];
  if (/^https?:/.test(page.url)) {
    dispatch.call(exports.tabs.on.unload, page);
  }
})
.on("activate", function(tab) {
  var page = pages[tab.id];
  if (!page || !page.active) {
    exports.log("[tabs.activate]", tab.id, tab.url);
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
      if (tab.window === windows.activeWindow && /^https?:/.test(page.url)) {
        dispatch.call(exports.tabs.on.focus, page);
      }
    }
  }
})
.on("deactivate", function(tab) {  // note: can fire after "close"
  exports.log("[tabs.deactivate]", tab.id, tab.url);
  if (tab.window === windows.activeWindow) {
    var page = pages[tab.id];
    if (page && /^https?:/.test(page.url)) {
      dispatch.call(exports.tabs.on.blur, page);
    }
  }
})
.on("ready", function(tab) {
  exports.log("[tabs.ready]", tab.id, tab.url);
  pages[tab.id] || createPage(tab);
});

windows
.on("open", function(win) {
  exports.log("[windows.open]", win.title);
  win.removeIcon = icon.addToWindow(win, onIconClick);
})
.on("close", function(win) {
  exports.log("[windows.close]", win.title);
  removeFromWindow(win);
})
.on("activate", function(win) {
  var page = pages[win.tabs.activeTab.id];
  if (page && /^https?:/.test(page.url)) {
    dispatch.call(exports.tabs.on.focus, page);
  }
})
.on("deactivate", function(win) {
  var page = pages[win.tabs.activeTab.id];
  if (page && /^https?:/.test(page.url)) {
    dispatch.call(exports.tabs.on.blur, page);
  }
});

for each (let win in windows) {
  if (!win.removeIcon) {
    exports.log("[windows] adding icon to window:", win.title);
    win.removeIcon = icon.addToWindow(win, onIconClick);
  }
  for each (let tab in win.tabs) {
    exports.log("[windows]", tab.id, tab.url);
    tabsById[tab.id] = tab;
    pages[tab.id] || createPage(tab);
  }
};

// attaching content scripts

let {PageMod} = require("sdk/page-mod");
PageMod({
  include: /^https?:.*/,
  contentScriptFile: url("scripts/start.js"),
  contentScriptWhen: "start",
  attachTo: ["existing", "top"],
  onAttach: function(worker) {
    var tab = worker.tab;
    exports.log("[onAttach]", tab.id, "start.js", tab.url);
    worker.port.on("api:start", function() {
      exports.log("[api:start]", tab.id, tab.url);
      var oldPage = pages[tab.id];
      if (oldPage && /^https?:/.test(oldPage.url)) {
        dispatch.call(exports.tabs.on.unload, oldPage);
      }
      var page = createPage(tab);
      var searchQuery = decodeURIComponent(((page.url.match(googleSearchPattern) || [])[4] || '').replace(/\+/g, ' '));
      if (searchQuery) dispatch.call(exports.on.search, searchQuery);
      dispatch.call(exports.tabs.on.loading, page);
    });
    worker.port.on("api:nav", function() {
      exports.log("[api:nav]", tab.id, tab.url);
      var oldPage = pages[tab.id];
      if (oldPage) {
        dispatch.call(exports.tabs.on.unload, oldPage);
      }
      dispatch.call(exports.tabs.on.loading, createPage(tab));
    });
  }});

timers.setTimeout(function() {  // async to allow main.js to complete (so portHandlers can be defined)
  require("./meta").contentScripts.forEach(function(arr) {
    var path = arr[0], urlRe = arr[1];
    var o = deps(path);
    exports.log("defining PageMod:", path, "deps:", o);
    PageMod({
      include: urlRe,
      contentStyleFile: o.styles.map(url),
      contentScriptFile: o.scripts.map(url),
      contentScriptWhen: "ready",
      contentScriptOptions: {dataUriPrefix: url(""), dev: prefs.env == "development"},
      attachTo: ["existing", "top"],
      onAttach: function(worker) {
        let tab = worker.tab, page = pages[tab.id];
        exports.log("[onAttach]", tab.id, this.contentScriptFile, tab.url, page);
        let injected = markInjected({}, o);
        let pw = workers[tab.id];
        pw.push(worker);
        worker.on("pageshow", function() {
          if (!page.ready && /^https?:/.test(page.url)) {
            exports.log("[pageshow] tab:", tab.id, "url:", tab.url);
            // marking and dispatching ready here instead of when tabs "ready" fires because:
            //  1. content scripts are not necessarily attached yet when tabs "ready" fires
            //  2. certain calls from content scripts fail if page is not yet visible
            //     (see https://bugzilla.mozilla.org/show_bug.cgi?id=766088#c2)
            page.ready = true;

            (page.toEmit || []).forEach(function emit(m) {
              exports.log("[pageshow:emit]", tab.id, m[0], m[1] != null ? m[1] : "");
              worker.port.emit.apply(worker.port, m);
            });
            delete page.toEmit;

            dispatch.call(exports.tabs.on.ready, page);  // must run only once per page, not per content script on page
          }
        }).on("pagehide", function() {
          exports.log("[pagehide] tab:", tab.id);
          pw.length = 0;
        }).on("detach", function() {
          exports.log("[detach] tab:", tab.id);
          pw.length = 0;
        });
        Object.keys(portHandlers).forEach(function(type) {
          worker.port.on(type, function(data, callbackId) {
            exports.log("[worker.port.on] message:", type, "data:", data, "callbackId:", callbackId);
            portHandlers[type](data, function(response) {
                worker.port.emit("api:respond", callbackId, response);
              }, page);
          });
        });
        worker.port.on("api:require", function(paths, callbackId) {
          var o = deps(paths, injected);
          exports.log("[api:require] tab:", tab.id, o);
          markInjected(injected, o);
          worker.port.emit("api:inject", o.styles.map(load), o.scripts.map(load), callbackId);
        });
      }});
  });
}, 0);

function removeFromWindow(win) {
  if (win.removeIcon) {
    win.removeIcon();
    delete win.removeIcon;
  }
}

exports.onUnload = function(reason) {
  for each (let win in windows) {
    removeFromWindow(win);
  }
};
