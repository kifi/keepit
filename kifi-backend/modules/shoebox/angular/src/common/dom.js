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
  }
});
