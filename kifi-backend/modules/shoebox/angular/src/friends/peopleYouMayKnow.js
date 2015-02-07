'use strict';

angular.module('kifi')

.directive('kfPeopleYouMayKnow', [
  '$log', '$q', '$rootScope', '$timeout',
  'friendService', 'socialService', 'inviteService', 'modalService', 'routeService', 'wtiService', 'profileService',
  function ($log, $q, $rootScope, $timeout,
            friendService, socialService, inviteService, modalService, routeService, wtiService, profileService) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/peopleYouMayKnow.tpl.html',
      link: function (scope) {
        var networkNamesMap = {
          facebook: 'Facebook',
          twitter: 'Twitter',
          linkedin: 'LinkedIn',
          email: 'email'
        };

        friendService.getPeopleYouMayKnow().then(function (people) {
          scope.people = people.map(function (person) {
            var numMutualFriends = person.mutualFriends.length || 0;
            return {
              id: person.id,
              fullName: person.firstName + ' ' + person.lastName,
              pictureUrl: routeService.formatPicUrl(person.id, person.pictureName, 100),
              profileUrl: routeService.getProfileUrl(person.username),
              actionText: 'Connect',
              clickable: true,
              isKifiUser: true,
              via: numMutualFriends > 0 ? '' : 'Kifi',
              numMutualFriends: numMutualFriends,
              mutualFriends: person.mutualFriends
            };
          });

          if (scope.people.length < 3) {
            (wtiService.list.length > 0 ? $q.when(wtiService.list) : wtiService.getMore()).then(function () {
              Array.prototype.push.apply(scope.people, wtiService.list.map(function (person) {
                return {
                  networkType: person.network,
                  id: person.identifier,
                  fullName: person.name,
                  pictureUrl: person.pictureUrl || 'https://www.kifi.com/assets/img/ghost.100.png',
                  actionText: 'Invite',
                  clickable: true,
                  isKifiUser: false,
                  via: person.network === 'email' && person.identifier || networkNamesMap[person.network]
                };
              }));
            });
          }
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

          _.remove(scope.people, function (elem) {
            return elem === person;
          });
        };

        scope.showMutualFriends = function (person) {
          modalService.open({
            template: 'friends/seeMutualFriendsModal.tpl.html',
            modalData: { savedPymk: person }
          });
        };


        function getEligibleNetworksCsv() {
          var onTwitterExperiment = _.indexOf(profileService.me.experiments, 'twitter_beta') > -1;
          return _.compact([
            socialService.facebook && socialService.facebook.profileUrl ? null : 'Facebook',
            !onTwitterExperiment || (socialService.twitter && socialService.twitter.profileUrl) ? null : 'Twitter',
            socialService.linkedin && socialService.linkedin.profileUrl ? null : 'LinkedIn',
            socialService.gmail && socialService.gmail.length ? null : 'Gmail'
          ]).join(',');
        }

        function chooseNetwork(csv) {
          scope.network = csv ? _.sample(csv.split(',')) : null;
        }

        scope.connectFacebook = socialService.connectFacebook;
        scope.connectTwitter = socialService.connectTwitter;
        scope.connectLinkedIn = socialService.connectLinkedIn;
        scope.importGmail = socialService.importGmail;


        //
        // Watches and listeners
        //
        scope.$watch(getEligibleNetworksCsv, chooseNetwork);

        //
        // Initialization
        //
        socialService.refresh();
      }
    };
  }
]);
