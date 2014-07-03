'use strict';

angular.module('kifi.minVersion', ['kifi.installService'])

.directive('kfMinVersion', [
  'installService',
  function (installService) {
    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        if (!installService.hasMinimumVersion(attrs.kfMinVersion || "0", attrs.minCanary)) {
          element.remove();
        }
      }
    };
  }
])

.directive('kfMaxVersion', [
  'installService',
  function (installService) {
    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        if (installService.hasMinimumVersion(attrs.kfMaxVersion || "0", attrs.minCanary)) {
          element.remove();
        }
      }
    };
  }
]);
