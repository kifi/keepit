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
      link: function (scope/*, element, attrs*/) {
        var friend = scope.request();
        scope.name = friend.firstName + ' ' + friend.lastName;
        scope.mainImage = routeService.formatPicUrl(friend.id, friend.pictureName, 200);
        scope.friendProfileUrl = routeService.getProfileUrl(friend.username);

        scope.accept = function () {
          friendService.acceptRequest(friend.id);
        };

        scope.ignore = function () {
          friendService.ignoreRequest(friend.id);
        };
      }
    };
  }
]);
