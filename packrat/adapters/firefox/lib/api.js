// API for main.js

function dispatch() {
  var args = arguments;
  this.forEach(function(f) {f.apply(null, args)});
}

// TODO: load some of these APIs on demand instead of up front
var self = require("sdk/self"), data = self.data, load = data.load.bind(data);
var timers = require("sdk/timers");
var tabs = require("sdk/tabs");
var mod = require("sdk/page-mod");
var privateBrowsing = require("sdk/private-browsing"); // TODO: if (!privateBrowsing.isActive) { ... }
var xulApp = require("sdk/system/xul-app");

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

var portHandlers = [];
exports.port = {
  on: function(handlers) {
    if (mods.length) throw Error("Please register all port handlers before any page mods.");
    portHandlers.push(handlers);
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
  require("request").Request(options)[method.toLowerCase()]();
};

var mods = [], workers = {}, nextWorkerId = 1;
exports.scripts = {
  register: function(urlRe, scripts, styles) {
    mods.push(mod.PageMod({
      include: urlRe,
      contentStyleFile: styles && styles.map(data.url.bind(data)),
      contentScriptFile: scripts && scripts.map(data.url.bind(data)),
      contentScriptWhen: "ready",
      contentScriptOptions: {dataUriPrefix: data.url("")},
      attachTo: ["existing", "top"],
      onAttach: function(worker) {
        var workerId = nextWorkerId++;
        workers[workerId] = worker;
        worker.on("detach", function() {
          delete workers[workerId];
        });
        portHandlers.forEach(function(handlers) {
          Object.keys(handlers).forEach(function(type) {
            worker.port.on(type, function(data, callbackId) {
              console.log("==== received message: " + type + " with data: " + JSON.stringify(data) + " and callbackId:" + callbackId);
              handlers[type](data, function(response) {
                  worker.port.emit("api_response", callbackId, response);
                }, {id: workerId, url: worker.url});
            });
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
      }}));
  }};

exports.storage = require("sdk/simple-storage").storage;

var nextCallbackId = 1, callbacks = {};
exports.tabs = {
  inject: function(tabId, details, callback) {
    var styles = (details.styles || []).map(load);
    var scripts = (details.scripts || []).map(load);
    if (details.script) {
      scripts.push(details.script);
    }
    var callbackId = nextCallbackId++;
    callbacks[callbackId] = callback;
    workers[tabId].port.emit("inject", styles, scripts, callbackId);
  },
  on: {
    activate: [],
    load: [],
    navigate: []}};

exports.timers = timers;
exports.version = self.version;
