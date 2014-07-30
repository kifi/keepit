'use strict';

angular.module('kifi.friends.rightColFriendsView', [])


.directive('kfCompactFriendsView', ['$log', 'friendService', function ($log, friendService) {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/compactFriendsView.tpl.html',
    link: function (scope/*, element, attrs*/) {
      // Configuration.
      scope.friendsDisplayLimit = 4;

      // Get number of friends.
      scope.friendCount = friendService.totalFriends;

      // Populate local friends list.
      friendService.getKifiFriends().then(function (data) {
        var actualFriends = _.filter(data, function (friend) {
          // Attach friend's picture url to local friends list.
          friend.pictureUrl = friendService.getPictureUrlForUser(friend);

          // Filter out friends who have been unfriended.
          return !friend.unfriended;
        });

        // Reorder local friends list to list friends with pics first.
        var hasPicFriends = [];
        var noPicFriends = [];

        actualFriends.forEach(function (friend) {
          // TODO(yiping): figure out when we can remove the '0.jpg.jpg' check.
          if ((friend.pictureName === '0.jpg') || (friend.pictureName === '0.jpg.jpg')) {
            noPicFriends.push(friend);
          } else {
            hasPicFriends.push(friend);
          }
        });

        // Expose local friends list on scope.
        scope.friends = hasPicFriends.concat(noPicFriends);
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

.directive('kfNoFriendsOrConnectionsView', ['socialService', function (routeService, socialService) {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/noFriendsOrConnectionsView.tpl.html',
    link: function (scope) {
      scope.connectFacebook = socialService.connectFacebook;
    }
  };
}]);
