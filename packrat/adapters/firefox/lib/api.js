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
var tabs = require("sdk/tabs");
var privateBrowsing = require("sdk/private-browsing"); // TODO: if (!privateBrowsing.isActive) { ... }
var xulApp = require("sdk/system/xul-app");
let {deps} = require("./deps");

// tabs.on('ready', function(tab) {
//   tab.attach({
//       contentScript:
//         'eval("console.log(7777777)");'
//   });
// });

exports.browserVersion = xulApp.name + "/" + xulApp.version;

exports.icon = {
  on: {
    click: []}};

exports.loadReason = {upgrade: "update", downgrade: "update"}[self.loadReason] || self.loadReason;
exports.log = function() {
  var d = new Date(), ds = d.toString();
  var args = Array.prototype.slice.apply(arguments);
  for (var i = 0; i < args.length; i++) {
    var arg = args[i];
    if (typeof arg == "object") {
      args[i] = JSON.stringify(arg);
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
    var win = require("windows").browserWindows.open({
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

var nextCallbackId = 1, callbacks = {};
exports.tabs = {
  inject: function(workerId, path, callback) {
    var o = deps(path, injected[workerId]);
    var callbackId = nextCallbackId++;
    callbacks[callbackId] = callback;
    workers[workerId].port.emit("inject", o.styles.map(load), o.scripts.map(load), callbackId);
  },
  on: {
    activate: [],
    loading: [],
    ready: [],
    complete: []}};

exports.timers = timers;
exports.version = self.version;

// attaching content scripts

var workers = {}, injected = {}, nextWorkerId = 1;
timers.setTimeout(function() {  // async to allow main.js to complete (so portHandlers can be defined)
  let {PageMod} = require("sdk/page-mod");
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
        worker.on("detach", function() {
          delete workers[workerId];
          delete injected[workerId];
        });
        Object.keys(portHandlers).forEach(function(type) {
          worker.port.on(type, function(data, callbackId) {
            exports.log("[worker.port.on] message:", type, "data:", data, "callbackId:", callbackId);
            portHandlers[type](data, function(response) {
                worker.port.emit("api_response", callbackId, response);
              }, {id: workerId, url: worker.url});
          });
        });
        worker.port.on("api_load", function(path, callbackId) {
          worker.port.emit("api_response", callbackId, data.load(path));
        });
        worker.port.on("api_response", function(callbackId, response) {
          var cb = callbacks[callbackId];
          if (cb) {
            delete callbacks[callbackId];
            cb(response);
          }
        });
      }});
  });
}, 0);
