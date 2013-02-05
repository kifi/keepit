function Listeners() {}
Listeners.prototype = [];
Listeners.prototype.constructor = Listeners;
Listeners.prototype.add = Listeners.prototype.push;
Listeners.prototype.remove = function(f) {
  var i;
  while ((i = this.indexOf(f)) >= 0) {
    this.splice(i, 1);
  }
};

this.exports && (this.exports.Listeners = Listeners);
