var ranges = ranges || (function () {
  'use strict';
  return {
    getClientRects: getClientRects,
    getBoundingClientRect: function (r, rects) {
      var bounds = (rects || getClientRects(r)).reduce(function (b, rect) {
        b.top = Math.min(b.top, rect.top);
        b.left = Math.min(b.left, rect.left);
        b.right = Math.max(b.right, rect.right);
        b.bottom = Math.max(b.bottom, rect.bottom);
        return b;
      }, {top: Infinity, left: Infinity, right: -Infinity, bottom: -Infinity});
      bounds.width = bounds.right - bounds.left;
      bounds.height = bounds.bottom - bounds.top;
      return bounds;
    }
  };

  function getClientRects(r) {  // crbug.com/324437
    var rects = [];
    var indexOf = Function.call.bind(Array.prototype.indexOf);
    var er = r.cloneRange();
    for (var el = er.endContainer; el !== er.commonAncestorContainer;) {
      var sr = er.cloneRange();
      sr.setStart(el, 0);
      var parent = el.parentNode;
      er.setEnd(parent, indexOf(parent.childNodes, el));
      rects.push.apply(rects, sr.getClientRects());
      el = parent;
    }
    rects.push.apply(rects, er.getClientRects());
    return rects;
  }
})();
