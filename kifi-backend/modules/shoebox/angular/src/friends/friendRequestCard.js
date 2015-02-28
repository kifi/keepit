'use strict';

angular.module('kifi')

.directive('kfFriendRequestCard', [
  'friendService',
  function (friendService) {
    return {
      scope: {
        'request': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/friendRequestCard.tpl.html',
      link: function (scope) {
        scope.friend = scope.request();

        scope.accept = function () {
          friendService.acceptRequest(scope.friend.id);
        };

        scope.ignore = function () {
          friendService.ignoreRequest(scope.friend.id);
        };
      }
    };
  }
]);
