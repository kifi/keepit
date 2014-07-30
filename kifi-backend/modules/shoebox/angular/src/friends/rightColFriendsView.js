'use strict';

angular.module('kifi.friends.rightColFriendsView', [])


.directive('kfCompactFriendsView', ['$log', 'friendService', function ($log, friendService) {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/compactFriendsView.tpl.html',
    link: function (scope/*, element, attrs*/) {
      scope.friendCount = friendService.totalFriends;

      friendService.getKifiFriends().then(function (data) {
        var actualFriends = _.filter(data, function (friend) {
          return !friend.unfriended;
        });

        actualFriends.forEach(function (friend) {
          friend.pictureUrl = friendService.getPictureUrlForUser(friend);
        });

        var hasPicture = function (friend) {
          return (friend.pictureName !== '0.jpg') && (friend.pictureName !== '0.jpg.jpg');
        };
        actualFriends.sort(function (friendA, friendB) {
          if (hasPicture(friendA) && !hasPicture(friendB)) {
            return -1;
          }

          if (!hasPicture(friendA) && hasPicture(friendB)) {
            return 1;
          }

          return 0;
        });

        scope.friends = actualFriends;
      });

      scope.friendsLink = function () {
        if (scope.friendCount() > 0) {
          return '/friends';
        } else {
          return '/invite';
        }
      };
    }
  };
}])

.directive('kfNoFriendsOrConnectionsView', ['socialService', function (socialService) {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/noFriendsOrConnectionsView.tpl.html',
    link: function (scope) {
      scope.connectFacebook = socialService.connectFacebook;
    }
  };
}]);
