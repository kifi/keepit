'use strict';

angular.module('kifi.friends.rightColFriendsView', [])

.directive('kfRightColFriendsView', [function () {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/rightColFriendsView.tpl.html'
  };
}])

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

.directive('kfRightColConnectView', ['friendService', 'socialService', 'util', function (friendService, socialService, util) {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/rightColConnectView.tpl.html',
    link: function (scope/*, element, attrs*/) {
      socialService.refresh();
      scope.friendCount = friendService.totalFriends;
      scope.showConnect = false;

      var connectionStatus = {
        'Facebook': socialService.facebook && !!socialService.facebook.profileUrl,
        'LinkedIn': socialService.linkedin && !!socialService.linkedin.profileUrl,
        'Gmail': socialService.gmail.length > 0
      };

      // The connection badge randomly rotates among networks that have not
      // been connected yet and is not shown if the user has connected to all
      // networks or has more than 20 friends.
      var updateConnectDisplayStatus = function () {
        // Array of unconnected networks.
        var connectOptions = [];

        _.forEach(connectionStatus, function (status, name) {
          if (!status) {
            connectOptions.push(name);
          }
        });

        if ((connectOptions.length === 0) || scope.friendCount >= 20) {
          scope.showConnect = false;
        } else {
          scope.showConnect = true;
        }

        var randomConnectOption = util.getRandomInt(0, connectOptions.length - 1);

        scope.showFacebookConnect = connectOptions[randomConnectOption] === 'Facebook';
        scope.showLinkedInConnect = connectOptions[randomConnectOption] === 'LinkedIn';
        scope.showGmailImport = connectOptions[randomConnectOption] === 'Gmail';
      };

      scope.$watch(function () {
        return socialService.facebook && !!socialService.facebook.profileUrl;
      }, function () {
        connectionStatus.Facebook = socialService.facebook && !!socialService.facebook.profileUrl;
        updateConnectDisplayStatus();
      });

      scope.$watch(function () {
        return socialService.linkedin && !!socialService.linkedin.profileUrl;
      }, function () {
        connectionStatus.LinkedIn = socialService.linkedin && !!socialService.linkedin.profileUrl;
        updateConnectDisplayStatus();
      });

      scope.$watch(function () {
        return socialService.gmail && socialService.gmail.length > 0;
      }, function () {
        connectionStatus.Gmail = socialService.gmail && socialService.gmail.length > 0;
        updateConnectDisplayStatus();
      });

      updateConnectDisplayStatus();
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
      scope.connectLinkedIn = socialService.connectLinkedIn;
      scope.importGmail = socialService.importGmail;
    }
  };
}]);
