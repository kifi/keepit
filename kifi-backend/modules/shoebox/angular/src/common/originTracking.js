'use strict';

angular.module('kifi')

.factory('originTrackingService', [
  function () {
    var pageOrigin = null;

    return {
      set: function (newOrigin) {
        pageOrigin = _.cloneDeep(newOrigin);
      },

      get: function () {
        var temp = pageOrigin;
        pageOrigin = null;
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
        origin: '@',
        subOrigin: '@'
      },
      link: function (scope, element /*, attrs*/) {
        element.on('click', function () {
          originTrackingService.set({
            origin: scope.origin,
            subOrigin: scope.subOrigin
          });
        });
      }
    };
  }
]);
