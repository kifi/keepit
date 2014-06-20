'use strict';

angular.module('kifi.friends.compactFriendsView', [])


.directive('kfCompactFriendsView', ['$log', 'friendService', function ($log, friendService) {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/compactFriendsView.tpl.html',
    link: function (scope/*, element, attrs*/) {
      scope.friendCount = friendService.totalFriends;
      friendService.getKifiFriends().then(function (data) {
        var actualFriends = _.filter(data, function (friend) {
          friend.pictureUrl = friendService.getPictureUrlForUser(friend);
          return !friend.unfriended;
        });
        var goodFriends = [];
        var badFriends = [];
        actualFriends.forEach(function (friend) {
          if (friend.pictureName==='0.jpg' || friend.pictureName==='0.jpg.jpg') {
            badFriends.push(friend);
          } else {
            goodFriends.push(friend);
          }
        });
        actualFriends = goodFriends.concat(badFriends);
        scope.friendGroups = [actualFriends.slice(0,5), actualFriends.slice(5,10)];
      });

      scope.friendsLink = function () {
        if (scope.friendCount() > 0) {
          return '/friends';
        } else {
          return '/invite';
        }
      }
    }
  };
}]);
