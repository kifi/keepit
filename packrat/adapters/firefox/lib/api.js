// API for main.js

function dispatch() {
  var args = arguments;
  this.forEach(function(f) {f.apply(null, args)});
}

function extend(a, b) {
  for (var k in b) {
    a[k] = b[k];
  }
  return a;
}

// TODO: load some of these APIs on demand instead of up front
const self = require("sdk/self"), data = self.data, load = data.load.bind(data), url = data.url.bind(url);
const timers = require("sdk/timers");
const xulApp = require("sdk/system/xul-app");
const { Ci, Cc } = require("chrome");
const WM = Cc["@mozilla.org/appshell/window-mediator;1"].getService(Ci.nsIWindowMediator);
const {deps} = require("./deps");
const {Listeners} = require("./listeners");
const icon = require("./icon");
const windows = require("sdk/windows").browserWindows;
const tabs = require("sdk/tabs");
const privateMode = require("sdk/private-browsing");

var nextTabId = 1;
const pages = {}, workers = {}, tabsById = {};  // all by tab.id
function createPage(tab) {
  if (!tab || !tab.id) throw Error(tab ? "tab without id" : "tab required");
  return pages[tab.id] = {id: tab.id, url: tab.url};
}

exports.bookmarks = require("./bookmarks");
exports.browserVersion = xulApp.name + "/" + xulApp.version;

exports.icon = {
  on: {click: new Listeners},
  set: function(page, path) {
    if (page === pages[page.id]) {
      page.icon = path;
      var tab = tabsById[page.id], win = tab.window;
      if (tab === win.tabs.activeTab) {
        icon.show(win, url(path));
      }
    }
  }};
function onIconClick(win) {
  dispatch.call(exports.icon.on.click, pages[win.tabs.activeTab.id]);
}

exports.loadReason = {upgrade: "update", downgrade: "update"}[self.loadReason] || self.loadReason;
exports.log = function() {
  var d = new Date(), ds = d.toString();
  var args = Array.prototype.slice.apply(arguments);
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
  console.log("[" + ds.substring(0,2) + ds.substring(15,24) + "." + String(+d).substring(10) + "]", args.join(" "));
};
exports.log.error = function(exception, context) {
  console.error((context ? "[" + context + "] " : "") + exception);
  console.error(exception.stack);
};

exports.noop = function() {};

exports.on = {
  install: new Listeners,
  update: new Listeners,
  startup: new Listeners};

// Call handlers for load reason async (after main.js has finished).
timers.setTimeout(dispatch.bind(exports.on[exports.loadReason]), 0);

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
      ((resp.status == 200 ? done : fail) || exports.noop)(resp.json || resp);
      done = fail = exports.noop;  // ensure we don't call a callback again
    }
  };
  if (data) {
    options.contentType = "application/json; charset=utf-8";
    options.content = JSON.stringify(data);
  }
  require("sdk/request").Request(options)[method.toLowerCase()]();
};

exports.storage = require("sdk/simple-storage").storage;

exports.tabs = {
  each: function(callback) {
    Object.keys(pages)
    .map(function(tabId) { return pages[tabId]; })
    .filter(function(page) { return /^https?:/.test(page.url); })
    .forEach(callback);
  },
  emit: function(tab, type, data) {
    if (tab === pages[tab.id]) {
      exports.log("[api.tabs.emit] tab:", tab.id, "type:", type, "data:", data, "url:", tab.url);
      workers[tab.id].forEach(function(worker) {
        worker.port.emit(type, data);
      });
    } else {
      exports.log.error(Error("tab " + tab.id + " no longer at " + tab.url), "api.tabs.emit:" + type);
    }
  },
  get: function(pageId) {
    return pages[pageId];
  },
  isFocused: function(page) {
    var tab = tabsById[page.id], win = tab.window;
    return win === windows.activeWindow && tab === win.tabs.activeTab;
  },
  isSelected: function(page) {
    var tab = tabsById[page.id];
    return tab === tab.window.tabs.activeTab;
  },
  on: {
    focus: new Listeners,
    blur: new Listeners,
    loading: new Listeners,
    ready: new Listeners,
    complete: new Listeners,
    unload: new Listeners}};

exports.timers = timers;
exports.version = self.version;

// initializing tabs and pages

tabs
.on("open", function(tab) {
  tab.id = tab.id || nextTabId++;
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
  if ((!page || !page.active) && !privateMode.isActive) {
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
  if (privateMode.isActive) return;
  exports.log("[tabs.ready]", tab.id, tab.url);
  pages[tab.id] || createPage(tab);
});

windows
.on("open", function(win) {
  if (privateMode.isActive) return;
  exports.log("[windows.open]", win.title);
  win.removeIcon = icon.addToWindow(win, onIconClick);
})
.on("close", function(win) {
  if (privateMode.isActive) return;
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
  if (privateMode.isActive) continue;
  if (!win.removeIcon) {
    exports.log("[windows] adding icon to window:", win.title);
    win.removeIcon = icon.addToWindow(win, onIconClick);
  }
  for each (let tab in win.tabs) {
    if (!tab.id) {
      tab.id = nextTabId++;
      exports.log("[windows]", tab.id, tab.url);
      tabsById[tab.id] = tab;
    }
    let page = pages[tab.id] || createPage(tab);
    // TODO: initialize page.complete somehow
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
    if (privateMode.isActive) return;
    var tab = worker.tab;
    tab.id = tab.id || nextTabId++;
    exports.log("[onAttach]", tab.id, "start.js", tab.url);
    worker.port.on("api:start", function() {
      exports.log("[api:start]", tab.id, tab.url);
      var oldPage = pages[tab.id];
      if (oldPage && /^https?:/.test(oldPage.url)) {
        dispatch.call(exports.tabs.on.unload, oldPage);
      }
      dispatch.call(exports.tabs.on.loading, createPage(tab));
    });
    worker.port.on("api:complete", function() {
      exports.log("[api:complete]", tab.id, tab.url);
      var page = pages[tab.id] || createPage(tab);
      page.complete = true;
      dispatch.call(exports.tabs.on.complete, page);
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
      contentScriptOptions: {dataUriPrefix: url("")},
      attachTo: ["existing", "top"],
      onAttach: function(worker) {
        if (privateMode.isActive) return; // TODO: tell script not to do anything if privateMode.isActive
        let tab = worker.tab, page = pages[tab.id];
        exports.log("[onAttach]", tab.id, this.contentScriptFile, tab.url, page);
        let injected = extend({}, o.injected);
        let pw = workers[tab.id] || (workers[tab.id] = []);
        pw.push(worker);
        worker.on("pageshow", function() {
          if (!page.ready && /^https?:/.test(page.url)) {
            exports.log("[pageshow] tab:", tab.id, "url:", tab.url);
            // marking and dispatching ready here instead of when tabs "ready" fires because:
            //  1. content scripts are not necessarily attached yet when tabs "ready" fires
            //  2. certain calls from content scripts fail if page is not yet visible
            //     (see https://bugzilla.mozilla.org/show_bug.cgi?id=766088#c2)
            page.ready = true;
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
        worker.port.on("api:load", function(path, callbackId) {
          worker.port.emit("api:respond", callbackId, data.load(path));
        });
        worker.port.on("api:require", function(path, callbackId) {
          var o = deps(path, injected);
          exports.log("[api:require] tab:", tab.id, o);
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

privateMode.on("start", function() {
  for each (let win in windows) {
    removeFromWindow(win);
  }
});

exports.onUnload = function(reason) {
  for each (let win in windows) {
    removeFromWindow(win);
  }
};
