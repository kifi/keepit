'use strict';
/* global jQuery: false */

angular.module('kifi')

// intended for elements for which it's important to detect hovering
// even when the primary mouse button is down (doesn't match :hover)
.directive('kfHover', function () {
  return {
    restrict: 'A',
    link: function (scope, element) {
      var hovering;
      element
      .on('mouseover', function () {
        if (!hovering) {
          hovering = true;
          element.addClass('kf-hover');
        }
      })
      .on('mouseout', function (e) {
        var toEl = e.relatedTarget;
        if (hovering && (!toEl || (this !== toEl && !jQuery.contains(this, toEl)))) {
          hovering = false;
          element.removeClass('kf-hover');
        }
      });
    }
  };
});
