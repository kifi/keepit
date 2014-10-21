'use strict';

angular.module('kifi')

.directive('kfMinVersion', [
  'installService', '$timeout',
  function (installService, $timeout) {
    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        function hideNode() {
          if (!installService.hasMinimumVersion(attrs.kfMinVersion, attrs.minCanary) && element.length && element[0].style) {
            element[0].style.display = 'none';
          }
        }
        // This is because we're waiting on the extension to let us know it's version number.
        // A better solution is a single watch on $rootScope that watches the kifi version number,
        // and if it changes, notify everyone who cares. This is not a simple change, though, so
        // doing this for now.
        hideNode();
        $timeout(hideNode);
        $timeout(hideNode, 150);
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
          if (installService.hasMinimumVersion(attrs.kfMaxVersion, attrs.minCanary) && element.length && element[0].style) {
            element[0].style.display = 'none';
          }
        }
        // See note above.
        hideNode();
        $timeout(hideNode);
        $timeout(hideNode, 150);
      }
    };
  }
]);
