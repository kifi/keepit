'use strict';

angular.module('kifi')

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
])

.directive('kfMaxVersion', [
  'installService',
  function (installService) {
    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        if (installService.hasMinimumVersion(attrs.kfMaxVersion, attrs.minCanary)) {
          element.remove();
        }
      }
    };
  }
]);
