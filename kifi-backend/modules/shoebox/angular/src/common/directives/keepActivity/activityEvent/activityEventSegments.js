'use strict';

angular.module('kifi')

.directive('kfActivityEventSegments', [
  'extensionLiaison', 'RecursionHelper',
  function (extensionLiaison, RecursionHelper) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepActivity/activityEvent/activityEventSegments.tpl.html',
      scope: {
        segments: '=kfActivityEventSegments'
      },
      compile: function (element) {
        function link($scope) {
          $scope.openLookHere = function(event, segment) {
            event.preventDefault();
            extensionLiaison.openDeepLink(segment.url, segment.locator);
          };
        }
        return RecursionHelper.compile(element, link);
      }
    };
  }
]);
