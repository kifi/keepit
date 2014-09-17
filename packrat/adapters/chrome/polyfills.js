if (!Array.prototype.findIndex) {
  Array.prototype.findIndex = function (predicate, thisArg) {
    'use strict';
    for (var i = 0, n = this.length; i < n; i++) {
      if (predicate.call(thisArg, this[i], i, this)) {
        return i;
      }
    }
    return -1;
  };
}
