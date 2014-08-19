'use strict';

angular.module('kifi')


.directive('kfFriendCard', [
  '$log', 'friendService',
  function ($log, friendService) {
    return {
      scope: {
        'friend': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/friendCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        var friend = scope.friend();
        scope.name = friend.firstName + ' ' + friend.lastName;
        if (friend.firstName[friend.firstName.length - 1] === 's') {
          scope.possesive = friend.firstName + '\'';
        } else {
          scope.possesive = friend.firstName + 's';
        }
        scope.mainImage = friendService.getPictureUrlForUser(friend);
        scope.friendCount = friend.friendCount;
        scope.unfriended = friend.unfriended;
        scope.searchFriend = friend.searchFriend;

        scope.unfriend = function () {
          scope.showUnfriendConfirm = true;
        };

        scope.reallyUnfriend = function () {
          friendService.unfriend(friend.id);
        };

        scope.unsearchfriend = function () {
          friendService.unSearchFriend(friend.id);
        };

        scope.researchfriend = function () {
          friendService.reSearchFriend(friend.id);
        };

      }
    };
  }
]);
