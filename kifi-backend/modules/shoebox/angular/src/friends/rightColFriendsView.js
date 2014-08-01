'use strict';

angular.module('kifi.friends.rightColFriendsView', [])
.directive('kfCompactFriendsView', ['friendService', function (friendService) {
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
          return friend.pictureName !== '0.jpg';
        };
        actualFriends.sort(function (friendA, friendB) {
          return -hasPicture(friendA) + hasPicture(friendB);
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

.directive('kfRightColConnectView', ['friendService', 'socialService', function (friendService, socialService) {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/rightColConnectView.tpl.html',
    link: function (scope/*, element, attrs*/) {
      function getEligibleNetworksCsv() {
        if (friendService.totalFriends() < 20) {
          return _.compact([
            socialService.facebook && socialService.facebook.profileUrl ? null : 'facebook',
            socialService.linkedin && socialService.linkedin.profileUrl ? null : 'linkedin',
            socialService.gmail && socialService.gmail.length ? null : 'gmail'
          ]).join(',');
        } else {
          return '';
        }
      }

      function chooseNetwork(csv) {
        scope.network = csv ? _.sample(csv.split(',')) : null;
      }
      
      scope.connectFacebook = socialService.connectFacebook;
      scope.connectLinkedIn = socialService.connectLinkedIn;
      scope.importGmail = socialService.importGmail;

      socialService.refresh();
      scope.$watch(getEligibleNetworksCsv, chooseNetwork);
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
