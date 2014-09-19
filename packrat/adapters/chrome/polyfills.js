if (!Array.prototype.find) {
  Object.defineProperty(Array.prototype, 'find', {
    value: function (predicate, thisArg) {
      for (var i = 0, n = this.length, val; i < n; i++) {
        if (predicate.call(thisArg, (val = this[i]), i, this)) {
          return val;
        }
      }
    }
  });
}
if (!Array.prototype.findIndex) {
  Object.defineProperty(Array.prototype, 'findIndex', {
    value: function (predicate, thisArg) {
      'use strict';
      for (var i = 0, n = this.length; i < n; i++) {
        if (predicate.call(thisArg, this[i], i, this)) {
          return i;
        }
      }
      return -1;
    }
  });
}
