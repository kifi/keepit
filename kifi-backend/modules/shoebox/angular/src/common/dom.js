'use strict';

angular.module('dom', [])

.value('dom', {
  scrollIntoViewLazy: function (el, padding) {
    var view;
    if (!(el && (view = el.offsetParent))) {
      return;
    }

    var viewTop = view.scrollTop,
      viewHeight = view.clientHeight,
      viewBottom = viewTop + viewHeight,
      elemTop = el.offsetTop,
      elemBottom = elemTop + el.offsetHeight;

    if (elemBottom > viewBottom) {
      view.scrollTop = elemBottom + (padding || 0) - viewHeight;
    }
    else if (elemTop < viewTop) {
      view.scrollTop = elemTop - (padding || 0);
    }
  },

  absOffsets: function (el) {
    var x = 0,
      y = 0;

    while (el) {
      x += el.offsetLeft;
      y += el.offsetTop;
      el = el.offsetParent;
    }

    return {
      x: x,
      y: y
    };
  }
});
