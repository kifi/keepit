'use strict';

angular.module('kifi')

.directive('kfSetHeightPx', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      link: function (scope, element) {
        $timeout(function() {
          if (element[0]) {
            element.css('height', element[0].offsetHeight + 'px');
          }
        }, 1);

      }
    };
  }
]);
