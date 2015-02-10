'use strict';

angular.module('kifi')

.directive('kfMinVersion', [
  'installService', '$rootScope',
  function (installService, $rootScope) {
    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        scope.$on('$destroy', $rootScope.$on('kifiExt', function updateNodeDisplay() {
          if (element.length && element[0].style) {
            if (!installService.hasMinimumVersion(attrs.kfMinVersion, attrs.minCanary)) {
              element[0].style.display = 'none';
            } else {
              element[0].style.display = 'initial';
            }
          }
        }));
      }
    };
  }
])

.directive('kfMaxVersion', [
  'installService', '$timeout',
  function (installService, $timeout) {
    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        function hideNode() {
          if (element.length && element[0].style) {
            if (installService.hasMinimumVersion(attrs.kfMinVersion, attrs.minCanary)) {
              element[0].style.display = 'none';
            } else {
              element[0].style.display = 'initial';
            }
          }
        }
        // See note above.
        hideNode();
        _.map([1, 150, 350, 1000], function (delay) { $timeout (hideNode, delay); });
      }
    };
  }
]);
