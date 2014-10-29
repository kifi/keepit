'use strict';

angular.module('kifi')

.directive('kfMinVersion', [
  'installService', '$timeout',
  function (installService, $timeout) {
    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        function hideNode() {
          if (element.length && element[0].style) {
            if (!installService.hasMinimumVersion(attrs.kfMinVersion, attrs.minCanary)) {
              element[0].style.display = 'none';
            } else {
              element[0].style.display = 'initial';
            }
          }
        }
        // This is because we're waiting on the extension to let us know it's version number.
        // A better solution is a single watch on $rootScope that watches the kifi version number,
        // and if it changes, notify everyone who cares. This is not a simple change, though, so
        // doing this for now.
        hideNode();
        _.map([1, 150, 350, 1000], function (delay) { $timeout (hideNode, delay); });
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
