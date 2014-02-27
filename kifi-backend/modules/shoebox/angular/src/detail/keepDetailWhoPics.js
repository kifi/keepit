'use strict';

angular.module('kifi.keepDetailWhoPics', ['kifi.keepWhoService'])


.directive('kfFriendCard', [
  function() {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        name: '@',
        picUri: '@'
      },
      templateUrl: 'detail/friendCard.tpl.html'
    }
  }
])

.directive('kfKeepDetailWhoPic', [
  'keepWhoService',
  function(keepWhoService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'detail/keepDetailWhoPic.tpl.html',
      scope: {
        keeper: '='
      },
      link: function(scope) {
        scope.tooltipEnabled = false;
        scope.getPicUrl = keepWhoService.getPicUrl;
        scope.getName = keepWhoService.getName;

        scope.showTooltip = function() {
          scope.tooltipEnabled = true;
        }

        scope.hideTooltip = function() {
          scope.tooltipEnabled = false;
        }
      }
    }
  }
])

.directive('kfKeepDetailWhoPics', [
  'keepWhoService',
  function (keepWhoService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'detail/keepDetailWhoPics.tpl.html',
      scope: {
        me: '=',
        keepers: '='
      },
      link: function(scope) {
        scope.getPicUrl = keepWhoService.getPicUrl;
        scope.getName = keepWhoService.getName;
      }
    };
  }
]);
