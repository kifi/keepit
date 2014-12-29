'use strict';

angular.module('kifi')


.directive('kfFriendRequestCard', [
  '$log', 'friendService', 'routeService', 'userService',
  function ($log, friendService, routeService, userService) {
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
        scope.friendProfileUrl = userService.getProfileUrl(friend.username);
        scope.inUserProfileBeta = userService.inUserProfileBeta();

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
