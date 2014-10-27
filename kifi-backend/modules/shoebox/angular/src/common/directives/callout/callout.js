'use strict';

angular.module('kifi')

.directive('kfCallout', [
  function () {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      transclude: true,
      templateUrl: 'common/directives/callout/callout.tpl.html',
      link: function (scope, element, attrs) {
        if (typeof attrs.relPos !== 'undefined') {
          var wrap = angular.element('<div>').css('position', 'relative');
          element.wrap(wrap);
        }
      }
    };
  }
]);
