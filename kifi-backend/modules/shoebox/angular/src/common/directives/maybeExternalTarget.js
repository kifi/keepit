'use strict';

angular.module('kifi')

.directive('kfMaybeExternalTarget', [
  '$window',
  function ($window) {
    return {
      restrict: 'A',
      link: function ($scope, element, attrs) {
        $scope.$watch(attrs.href, function () {
          if (element[0].host === $window.location.host) {
            element.attr('target', undefined);
          } else {
            element.attr('target', '_blank');
          }
        });
      }
    };
  }
]);
