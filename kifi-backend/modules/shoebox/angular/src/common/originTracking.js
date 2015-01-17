'use strict';

angular.module('kifi')

.factory('originTrackingService', [
  function () {
    var pageOrigin = '';

    return {
      set: function (newOrigin) {
        pageOrigin = newOrigin || '';
      },

      getAndClear: function () {
        var temp = pageOrigin;
        pageOrigin = '';
        return temp;
      }
    };
  }
])

.directive('kfTrackOrigin', [
  'originTrackingService',
  function (originTrackingService) {
    return {
      restrict: 'A',
      scope: {
        origin: '@'
      },
      link: function (scope, element /*, attrs*/) {
        element.on('click', function () {
          originTrackingService.set(scope.origin);
        });
      }
    };
  }
]);
