'use strict';

angular.module('kifi')

.directive('kfFriendRequestCard', [
  '$log', 'friendService', 'routeService',
  function ($log, friendService, routeService) {
    return {
      scope: {
        'request': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/friendRequestCard.tpl.html',
      link: function (scope) {
        scope.friend = scope.request();
        scope.friendProfileUrl = routeService.getProfileUrl(scope.friend.username);

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
