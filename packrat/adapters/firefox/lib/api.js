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
var self = require("sdk/self"), data = self.data, load = data.load.bind(data), url = data.url.bind(url);
var timers = require("sdk/timers");
var privateBrowsing = require("sdk/private-browsing"); // TODO: if (!privateBrowsing.isActive) { ... }
var xulApp = require("sdk/system/xul-app");
let {deps} = require("./deps");

var pages = {}, pagesByTabId = {}, nextPageId = 1, nextTabId = 1;

exports.bookmarks = require("./bookmarks");
exports.browserVersion = xulApp.name + "/" + xulApp.version;

exports.icon = {
  on: {click: []},
  set: function(pageId, path) {
    var page = pages[pageId];
    if (page) {
      page.icon = path;
    }
    button.setImage(url(path));
  }};

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
    .map(function(pageId) { return pages[pageId]; })
    .filter(function(page) { return /^https?:/.test(page.url); })
    .forEach(callback);
  },
  emit: function(pageId, type, data) {
    exports.log("[api.tabs.emit] tab:", pageId, "type:", type, "data:", data);
    var pw = pageWorkers[pageId];
    if (pw) {
      pw.forEach(function(worker) {
        worker.port.emit(type, data);
      });
    } else {
      exports.log.error(Error("no page " + pageId), "activate");
    }
  },
  get: function(pageId) {
    return pages[pageId];
  },
  on: {
    activate: [],
    loading: [],
    ready: [],
    complete: []}};

exports.timers = timers;
exports.version = self.version;

// initializing tabs and pages

var tabs = require("sdk/tabs")
.on("open", function(tab) {
  tab.id = ++nextTabId;
  exports.log("[tabs.open]", tab.id, tab.url);
})
.on("close", function(tab) {
  exports.log("[tabs.close]", tab.id, tab.url);
  var page = pagesByTabId[tab.id];
  if (page) {
    delete pagesByTabId[tab.id];
    delete pages[page.id];
  }
})
.on("activate", function(tab) {
  exports.log("[tabs.activate]", tab.id, tab.url);
  tab.active = true;
  if (tab.url !== "about:blank") {
    var page = pagesByTabId[tab.id];
    if (page) {
      page.active = true;
      dispatch.call(exports.tabs.on.activate, page);
    } else {
      exports.log.error(Error("no page for tab " + tab.id), "activate");
    }
  }
})
.on("deactivate", function(tab) {
  exports.log("[tabs.deactivate]", tab.id, tab.url);
  tab.active = false;
  var page = pagesByTabId[tab.id];
  if (page) {
    page.active = false;
  }
})
.on("ready", function(tab) {
  exports.log("[tabs.ready]", tab.id, tab.url);
  var page = pagesByTabId[tab.id];
  page.ready = true;
  dispatch.call(exports.tabs.on.ready, page);  // TODO: ensure content scripts are fully injected before dispatch
});

var windows = require("windows").browserWindows;
for (let win in windows) {
  let activeTab = win.activeTab;
  for (let tab in win.tabs) {
    if (!tab.id) {
      tab.id = ++nextTabId;
      tab.active = tab === activeTab;
      exports.log("[windows] tab", tab.id, "at", tab.url, "active:", tab.active);
    }
    let page = pagesByTabId[tab.id];
    if (page) {
      page.active = tab.active;
    } else {
      page = pagesByTabId[tab.id] = {
        id: ++nextPageId,
        url: tab.url,
        active: tab.active};  // TODO: initialize page.ready somehow
      pages[page.id] = page;
    }
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
    worker.port.on("api:start", function() {
      exports.log("[api:start]", tab.id, tab.url);
      var page = {
        id: ++nextPageId,
        url: tab.url,
        active: tab.active};
      pages[page.id] = page;
      pagesByTabId[tab.id] = page;
      dispatch.call(exports.tabs.on.loading, page);
    });
    worker.port.on("api:complete", function() {
      exports.log("[api:complete]", tab.id, tab.url);
      var page = pagesByTabId[tab.id];
      if (!page) {
        page = pagesByTabId[tab.id] = {
          id: ++nextPageId,
          url: tab.url,
          active: tab.active};
        pages[page.id] = page;
      }
      page.ready = true;
      dispatch.call(exports.tabs.on.complete, page);
    });
    worker.port.on("api:nav", function() {
      exports.log("[api:nav]", tab.id, tab.url);
      var page = pagesByTabId[tab.id];
      if (!page) {
        page = pagesByTabId[tab.id] = {
          id: ++nextPageId,
          url: tab.url,
          active: tab.active};
        pages[page.id] = page;
      }
      dispatch.call(exports.tabs.on.loading, page);
    });
  }});

var workers = {}, pageWorkers = {}, injected = {}, nextWorkerId = 1;
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
        var workerId = nextWorkerId++;
        workers[workerId] = worker;
        injected[workerId] = extend(injected[workerId] || {}, o.injected);
        let page = pagesByTabId[worker.tab.id], pw = pageWorkers[page.id];
        if (!pw) {
          pw = pageWorkers[page.id] = [worker];
        } else {
          pw.push(worker);
        }
        exports.log("page workers for page:", page.id, page.url, "are:", pw);
        worker.on("detach", function() {
          delete workers[workerId];
          delete injected[workerId];
          delete pageWorkers[page.id];
        });
        Object.keys(portHandlers).forEach(function(type) {
          worker.port.on(type, function(data, callbackId) {
            exports.log("[worker.port.on] message:", type, "data:", data, "callbackId:", callbackId);
            var page = pagesByTabId[worker.tab.id];
            portHandlers[type](data, function(response) {
                worker.port.emit("api:respond", callbackId, response);
              }, page);
          });
        });
        worker.port.on("api:load", function(path, callbackId) {
          worker.port.emit("api:respond", callbackId, data.load(path));
        });
        worker.port.on("api:require", function(path, callbackId) {
          var o = deps(path, injected[workerId]);
          worker.port.emit("api:inject", o.styles.map(load), o.scripts.map(load), callbackId);
        });
      }});
  });
}, 0);

let button = require("packages/barbutton/barbutton").BarButton({
  id: "firefox-barbutton",
  click: function() {
    dispatch.call(exports.icon.on.click, pagesByTabId[tabs.activeTab.id]);
  }});
