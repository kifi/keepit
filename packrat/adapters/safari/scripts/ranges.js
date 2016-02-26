window.ranges = window.ranges || (function () {
  'use strict';
  return {
    getClientRects: function (r) {
      return r.getClientRects();
    },
    getBoundingClientRect: function (r) {
      return r.getBoundingClientRect();
    }
  };
}());
