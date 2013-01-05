var handlers = {};
function fire(eventType) {
  var arr = handlers[eventType];
  if (arr) {
    arr.forEach(function(f) {f()});
  }
}

var self = require("sdk/self");
var timers = require("sdk/timers");
//var tabs = require("sdk/tabs");
exports.loadReason = {upgrade: "update", downgrade: "update"}[self.loadReason] || self.loadReason;
exports.messages = null;  // TODO
exports.off = function(event, callback) {
  var arr = handlers[event];
  if (arr) {
    var i = arr.indexOf(callback);
    if (i >= 0) {
      arr.splice(i, 1);
    }
  }
};
exports.on = function(event, callback) {
  var arr = handlers[event];
  if (!arr) {
    handlers[event] = [callback];
  } else {
    arr.push(callback);
  }
};
exports.timers = timers;
exports.version = self.version;

// fire handlers for load reasons async (after main.js has finished)
timers.setTimeout(function() {
  fire(exports.loadReason);
}, 0);
