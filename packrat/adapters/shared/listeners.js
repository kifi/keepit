function Listeners() {}
Listeners.prototype = [];
Listeners.prototype.constructor = Listeners;
Listeners.prototype.add = Listeners.prototype.push;
Listeners.prototype.remove = function(f) {
  'use strict';
  for (var i; ~(i = this.indexOf(f));) {
    this.splice(i, 1);
  }
};

this.exports && (this.exports.Listeners = Listeners);
