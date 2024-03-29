function Listeners() {
  this.arr = [];
}
Listeners.prototype.constructor = Listeners;
Listeners.prototype.add = function(f) {
  var i = this.arr.indexOf(f);
  if (i < 0) {
    this.arr.push(f);
  }
};
Listeners.prototype.remove = function(f) {
  var i = this.arr.indexOf(f);
  if (i >= 0) {
    this.arr.splice(i, 1);
  }
};
Listeners.prototype.dispatch = function() {
  for (var i = 0, n = this.arr.length; i < n; i++) {
    this.arr[i].apply(null, arguments);
  }
};
