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

      applyAndClear: function (obj) {
        if (!pageOrigin) {
          pageOrigin = pageOrigins[initParams.getAndClear('o')];
        }
        if (pageOrigin) {
          var parts = pageOrigin.split('/');
          obj.origin = parts[0];
          if (parts[1]) {
            obj.subOrigin = parts[1];
          }
          pageOrigin = '';
        }
        return obj;
      }
    };
  }
])

.directive('kfTrackOrigin', [
  'originTrackingService',
  function (originTrackingService) {
    return {
      restrict: 'A',
      link: function (scope, element) {
        element.on('click', function () {
          originTrackingService.set(element.attr('kf-track-origin'));
        });
      }
    };
  }
]);
