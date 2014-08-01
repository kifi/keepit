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

.directive('kfPeopleYouMayKnowView', 
  ['$log', '$timeout', 'friendService', 'inviteService', 'wtiService', 
  function ($log, $timeout, friendService, inviteService, wtiService) {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/peopleYouMayKnowView.tpl.html',
    link: function (scope/*, element, attrs*/) {
      friendService.getPeopleYouMayKnow().then(function (people) {
        var peopleYouMayKnow = [];

        people.forEach(function (person) {
          var name = person.firstName + ' ' + person.lastName;
          peopleYouMayKnow.push({
            id: person.id,
            fullName: name + ', ',
            pictureUrl: friendService.getPictureUrlForUser(person) || 'https://www.kifi.com/assets/img/ghost.100.png',
            actionText: 'Add',
            clickable: true,
            isKifiUser: true,
            via: 'via Kifi',
            squish: name.length > 21
          });
        });
        
        var networkNamesMap = {
          'facebook': 'Facebook',
          'linkedin': 'LinkedIn',
          'email': 'email'
        };

        if (peopleYouMayKnow.length < 3) {
          wtiService.getMore().then(function (wtiList) {
            wtiList.forEach(function (person) {
              var socialIdValues = person.fullSocialId.split('/');
              var name = '';
              var via = '';

              if (person.name) {
                name = person.name + ', ';
                via = 'via ' + networkNamesMap[socialIdValues[0]];
              } else if (socialIdValues[0] === 'email') {
                name = socialIdValues[1];
              }

              peopleYouMayKnow.push({
                networkType: socialIdValues[0],
                id: socialIdValues[1],
                fullName: name,
                pictureUrl: person.pictureUrl || 'https://www.kifi.com/assets/img/ghost.100.png',
                actionText: 'Invite',
                clickable: true,
                isKifiUser: false,
                via: via,
                squish: person.name && person.name.length > 17
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
