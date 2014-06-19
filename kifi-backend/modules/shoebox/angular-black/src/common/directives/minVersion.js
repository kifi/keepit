'use strict';

angular.module('kifi.minVersion', ['kifi.installService'])

.directive('kfMinVersion', [
  'installService',
  function (installService) {
    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        if (!installService.hasMinimumVersion(attrs.kfMinVersion, attrs.minCanary)) {
          element.remove();
        }
      }
    };
  }
]);
