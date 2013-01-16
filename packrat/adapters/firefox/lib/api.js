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
//const privateBrowsing = require("sdk/private-browsing"); // TODO: if (!privateBrowsing.isActive) { ... }
const xulApp = require("sdk/system/xul-app");
const { Ci, Cc } = require("chrome");
const WM = Cc["@mozilla.org/appshell/window-mediator;1"].getService(Ci.nsIWindowMediator);
const {deps} = require("./deps");
const icon = require("./icon");
const windows = require("sdk/windows").browserWindows;
const tabs = require("sdk/tabs");

var nextTabId = 1;
const pages = {}, workers = {};  // by tab.id
function createPage(tab) {
  if (!tab || !tab.id) throw Error(tab ? "tab without id" : "tab required");
  return pages[tab.id] = {id: tab.id, url: tab.url, active: tab === tab.window.tabs.activeTab, _win: tab.window};
}

exports.bookmarks = require("./bookmarks");
exports.browserVersion = xulApp.name + "/" + xulApp.version;

exports.icon = {
  on: {click: []},
  set: function(tabId, path) {
    var page = pages[tabId];
    exports.log("[icon.set]", tabId, path, page);
    if (page) {
      page.icon = path;
      if (page.active) {
        icon.show(page._win, url(path));
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
  install: [],
  update: [],
  startup: []};

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
      exports.log("[api.tabs.emit] tab:", tab.id, "type:", type, "data:", data);
      workers[tab.id].forEach(function(worker) {
        worker.port.emit(type, data);
      });
    } else {
      exports.log.error(Error("tab " + tab.id + " no longer at " + tab.url), "api.tabs.emit:" + type);
    }
  },
  get: function(tabId) {
    return pages[tabId];
  },
  on: {
    activate: [],
    loading: [],
    ready: [],
    complete: []}};

exports.timers = timers;
exports.version = self.version;

// initializing tabs and pages

tabs
.on("open", function(tab) {
  tab.id = tab.id || nextTabId++;
  exports.log("[tabs.open]", tab.id, tab.url);
})
.on("close", function(tab) {
  exports.log("[tabs.close]", tab.id, tab.url);
  delete pages[tab.id];
  delete workers[tab.id];
})
.on("activate", function(tab) {
  var page = pages[tab.id];
  if (!page || !page.active) {
    exports.log("[tabs.activate]", tab.id, tab.url);
    if (!/^about:/.test(tab.url)) {
      if (page) {
        page.active = true;
        if (page.icon) {
          icon.show(tab.window, url(page.icon));
        } else {
          icon.hide(tab.window);
        }
      } else {
        page = createPage(tab);
      }
      dispatch.call(exports.tabs.on.activate, page);
    }
  }
})
.on("deactivate", function(tab) {
  if (tab.window === windows.activeWindow) {
    exports.log("[tabs.deactivate]", tab.id, tab.url);
    var page = pages[tab.id];
    if (page) {
      page.active = false;
    }
  }
})
.on("ready", function(tab) {
  exports.log("[tabs.ready]", tab.id, tab.url);
  var page = pages[tab.id] || createPage(tab);
  page.ready = true;
  dispatch.call(exports.tabs.on.ready, page);  // TODO: ensure content scripts are fully injected before dispatch
});

windows
.on("open", function(win) {
  exports.log("[windows.open]", win.title);
  win.removeIcon = icon.addToWindow(win, onIconClick);
})
.on("close", function(win) {
  exports.log("[windows.close]", win.title);
  removeFromWindow(win);
});

for each (let win in windows) {
  if (!win.removeIcon) {
    exports.log("[windows] adding icon to window:", win.title);
    win.removeIcon = icon.addToWindow(win, onIconClick);
  }
  for each (let tab in win.tabs) {
    if (!tab.id) {
      tab.id = nextTabId++;
      exports.log("[windows]", tab.id, tab.url);
    }
    let page = pages[tab.id];
    if (page) {
      page.active = tab === tab.window.tabs.activeTab;
    } else {
      createPage(tab);
    }
    // TODO: initialize page.ready somehow
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
    tab.id = tab.id || nextTabId++;
    exports.log("[onAttach]", tab.id, "start.js", tab.url);
    worker.port.on("api:start", function() {
      exports.log("[api:start]", tab.id, tab.url);
      dispatch.call(exports.tabs.on.loading, createPage(tab));
    });
    worker.port.on("api:complete", function() {
      exports.log("[api:complete]", tab.id, tab.url);
      var page = pages[tab.id] || createPage(tab);
      page.ready = true;
      dispatch.call(exports.tabs.on.complete, page);
    });
    worker.port.on("api:nav", function() {
      exports.log("[api:nav]", tab.id, tab.url);
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
        let tab = worker.tab, page = pages[tab.id];
        exports.log("[onAttach]", tab.id, this.contentScriptFile, tab.url, page);
        let injected = extend({}, o.injected);
        let pw = workers[tab.id] || (workers[tab.id] = []);
        pw.push(worker);
        worker.on("detach", function() {
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

exports.onUnload = function(reason) {
  windows.forEach(removeFromWindow);
};
