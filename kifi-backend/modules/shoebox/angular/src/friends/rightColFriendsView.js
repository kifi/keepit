'use strict';

angular.module('kifi.friends.rightColFriendsView', [
  'kifi',
  'kifi.friendService',
  'kifi.inviteService',
  'kifi.invite.wtiService',
  'kifi.socialService'
])

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

        // Randomly select 4 friends to display, but always display
        // friends with pictures before friends without pictures.
        var pictureGroups = _.groupBy(actualFriends, function (friend) {
          return friend.pictureName !== '0.jpg';
        });
        var friendsToDisplay = _.sample(pictureGroups['true'], 4);
        if (friendsToDisplay.length < 4) {
          friendsToDisplay = friendsToDisplay.concat(
            _.sample(pictureGroups['false'], 4 - friendsToDisplay.length)
          );
        }

        friendsToDisplay.forEach(function (friend) {
          friend.pictureUrl = friendService.getPictureUrlForUser(friend);
        });
        
        scope.friends = friendsToDisplay;
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

.directive('kfPeopleYouMayKnowView', 
  ['$log', '$q', '$rootScope', '$timeout', 'friendService', 'inviteService', 'savePymkService', 'wtiService', 
  function ($log, $q, $rootScope, $timeout, friendService, inviteService, savePymkService, wtiService) {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/peopleYouMayKnowView.tpl.html',
    link: function (scope/*, element, attrs*/) {
      friendService.getPeopleYouMayKnow().then(function (people) {
        var peopleYouMayKnow = [];

        people.forEach(function (person) {
          var name = person.firstName + ' ' + person.lastName;
          var numMutualFriends = person.mutualFriends.length || 0;

          peopleYouMayKnow.push({
            id: person.id,
            fullName: name,
            pictureUrl: friendService.getPictureUrlForUser(person),
            actionText: 'Add',
            clickable: true,
            isKifiUser: true,
            via: numMutualFriends > 0 ? '' : 'Kifi',
            numMutualFriends: numMutualFriends,
            mutualFriends: person.mutualFriends
          });
        });

        var networkNamesMap = {
          'facebook': 'Facebook',
          'linkedin': 'LinkedIn',
          'email': 'email'
        };

        if (peopleYouMayKnow.length < 3) {
          (wtiService.list.length > 0 ? $q.when(wtiService.list) : wtiService.getMore()).then(function () {
            var wtiList = wtiService.list;

            wtiList.forEach(function (person) {
              var name = '';
              var via = '';

              if (person.name) {
                name = person.name;
                via = networkNamesMap[person.network];
              } else if (person.network === 'email') {
                name = person.identifier;  // Email address
              }

              peopleYouMayKnow.push({
                networkType: person.network,
                id: person.identifier,
                fullName: name,
                pictureUrl: person.pictureUrl || 'https://www.kifi.com/assets/img/ghost.100.png',
                actionText: 'Invite',
                clickable: true,
                isKifiUser: false,
                via: via
              });
            });
          });
        }

        scope.peopleYouMayKnow = peopleYouMayKnow;
      });

      scope.action = function (person) {
        if (!person.clickable) {
          return;
        }
        person.clickable = false;

        // Request to be friends with existing Kifi user.
        if (person.isKifiUser) {
          inviteService.friendRequest(person.id).then(function () {
            person.actionText = 'Sent!';
            $timeout(function () {
              person.actionText = 'Resend';
              person.clickable = true;
            }, 4000);
            inviteService.expireSocialSearch();
          }, function () {
            person.actionText = 'Error. Retry?';
            person.clickable = true;
            inviteService.expireSocialSearch();
          });
        } 

        // Invite contact to become Kifi user.
        else {
          inviteService.invite(person.networkType, person.id).then(function () {
            person.actionText = 'Sent!';
            $timeout(function () {
              person.actionText = 'Resend';
              person.clickable = true;
            }, 4000);
            inviteService.expireSocialSearch();
          }, function () {
            person.actionText = 'Error. Retry?';
            person.clickable = true;
            inviteService.expireSocialSearch();
          });
        }
      };

      scope.remove = function (person) {
        if (person.isKifiUser) {
          friendService.hidePeopleYouMayKnow(person.id);
        }

        _.remove(scope.peopleYouMayKnow, function (elem) {
          return elem === person;
        });
      };

      scope.showMutualFriends = function (person) {
        savePymkService.savePersonYouMayKnow(person);
        $rootScope.$emit('showGlobalModal', 'seeMutualFriends');
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
