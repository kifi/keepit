function dispatch(a) {
  if (a) a.forEach(function(f) {f()});
}

var self = require("sdk/self"), data = self.data;
var pageMod = require("page-mod");
var timers = require("sdk/timers");
//var tabs = require("sdk/tabs");

exports.loadReason = {upgrade: "update", downgrade: "update"}[self.loadReason] || self.loadReason;
exports.on = {
  install: [],
  update: [],
  startup: []};

var portHandlers = [];
exports.port = {
  on: function(handlers) {
    portHandlers.push(handlers);
    // TOOD: Register all handlers with each pageMod.
    // portHandlers.forEach(handlers) {
    //   for (var type in handlers) {
    //     if (handlers.hasOwnProperty(type)) {
    //       worker.port.on(type, handlers[type]);
    //     }
    //   }
    // }
  }};

pageMod.PageMod({
  include: "*.mozilla.org",
  contentScriptFile: data.url("my-script.js")
});

exports.timers = timers;
exports.version = self.version;

// fire handlers for load reasons async (after main.js has finished)
timers.setTimeout(function() {
  dispatch(exports.on[exports.loadReason]);
}, 0);
