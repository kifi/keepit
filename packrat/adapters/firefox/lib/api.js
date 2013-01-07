function dispatch(a) {
  if (a) a.forEach(function(f) {f()});
}

var self = require("sdk/self");
var timers = require("sdk/timers");
//var tabs = require("sdk/tabs");
exports.loadReason = {upgrade: "update", downgrade: "update"}[self.loadReason] || self.loadReason;
exports.port = null;  // TODO
exports.on = {
  install: [],
  update: [],
  startup: []};
exports.timers = timers;
exports.version = self.version;

// fire handlers for load reasons async (after main.js has finished)
timers.setTimeout(function() {
  fire(exports.loadReason);
}, 0);
