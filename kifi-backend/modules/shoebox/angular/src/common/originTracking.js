'use strict';

angular.module('kifi')

.factory('originTrackingService', [
  'initParams',
  function (initParams) {
    var pageOrigin = '';
    var pageOrigins = {
      xmp: 'extension/messagePane',
      xsr: 'extension/searchResult',
      xst: 'extension/socialTooltip'
    };

    return {
      set: function (newOrigin) {
        pageOrigin = newOrigin || '';
      },

      getAndClear: function () {
        var temp = pageOrigin;
        if (temp) {
          pageOrigin = '';
          return temp;
        }
        temp = pageOrigins[initParams.o];
        if (temp) {
          delete initParams.o;
          return temp;
        }
        return '';
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
