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
