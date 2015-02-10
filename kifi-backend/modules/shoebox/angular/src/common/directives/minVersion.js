'use strict';

angular.module('kifi')

.directive('kfMinVersion', [
  'installService', '$rootScope',
  function (installService, $rootScope) {
    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        scope.$on('$destroy', $rootScope.$on('kifiExt', function updateNodeDisplay() {
          element.css('display', installService.hasMinimumVersion(attrs.kfMinVersion, attrs.minCanary) ? '' : 'none');
        }));
      }
    };
  }
]);
