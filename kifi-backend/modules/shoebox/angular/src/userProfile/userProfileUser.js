'use strict';

angular.module('kifi')

.directive('kfUserProfileUser', [
  '$timeout', 'modalService', 'profileService', 'userProfileActionService', 'friendService', 'inviteService', 'signupService',
  function ($timeout, modalService, profileService, userProfileActionService, friendService, inviteService, signupService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        user: '=kfUserProfileUser'
      },
      templateUrl: 'userProfile/userProfileUser.tpl.html',
      link: function (scope) {
        if (scope.$root.userLoggedIn) {
          scope.me = profileService.me;

          // unpacking information about relationship, since it's (confusingly) not always about the user on this card
          scope.mutual = _.pick(  // TODO: pass profile all the way down?
            scope.me && scope.me.id === scope.user.id ? scope.$parent.$parent.$parent.profile : scope.user,
            'id', 'firstName', 'lastName', 'pictureName', 'username');
          scope.mutual.connections = scope.user.mConnections;
          scope.mutual.libraries = scope.user.mLibraries;
          _.assign(scope.mutual, _.pick(scope.user, 'isFriend', 'friendRequestSentAt', 'friendRequestReceivedAt'));
          ['mConnections','mLibraries','isFriend','friendRequestSentAt','friendRequestReceivedAt'].forEach(function (key) {
            delete scope.user[key];
          });
        }

        scope.showMutualConnections = function () {
          userProfileActionService.getMutualConnections(scope.mutual.id).then(function (data) {
            var person = _.pick(scope.mutual, 'id', 'username', 'pictureName', 'isFriend');
            person.fullName = scope.mutual.firstName + ' ' + scope.mutual.lastName;
            person.numMutualFriends = scope.mutual.connections;
            person.mutualFriends = data.users;
            modalService.open({
              template: 'friends/seeMutualFriendsModal.tpl.html',
              modalData: person
            });
          });
        };

        scope.onClickPrimaryButton = function () {
          if (scope.friendStatusChanging) {
            return;
          } else if (!scope.$root.userLoggedIn) {
            signupService.register({toConnectWith: scope.user});
          } else if (scope.mutual.isFriend) {
            modalService.open({
              template: 'friends/unfriendConfirmModal.tpl.html',
              scope: _.assign(scope.$root.$new(), {friend: scope.mutual, reallyUnfriend: reallyUnfriend})
            });
          } else if (!scope.mutual.friendRequestSentAt) {
            var btnDuration = 600;  // easier to duplicate from stylesheet than to read from element
            var minimumDuration = $timeout(angular.noop, btnDuration / 2 + 160);  // added delay to avoid bouncing feeling
            scope.friendStatusChanging = true;
            inviteService.friendRequest(scope.mutual.id).then(function (data) {
              minimumDuration.then(function () {
                delete scope.mutual.friendRequestReceivedAt; // just to be sure, old server bug made it possible
                if (data.sentRequest) {
                  scope.mutual.friendRequestSentAt = Date.now();
                } else if (data.acceptedRequest || data.alreadyConnected) {
                  scope.mutual.isFriend = true;
                }
                scope.friendStatusChanging = false;
              });
            }, function () {
              scope.friendStatusChanging = false;
            });
          }
        };

        scope.accept = function () {
          friendService.acceptRequest(scope.mutual.id).then(function () {
            scope.mutual.isFriend = true;
            scope.mutual.friendRequestReceivedAt = null;
          });
        };

        scope.decline = function () {
          scope.mutual.friendRequestReceivedAt = null;
          friendService.ignoreRequest(scope.mutual.id);
        };

        function reallyUnfriend() {
          scope.mutual.isFriend = false;
          friendService.unfriend(scope.mutual.id).then(angular.noop, function error() {
            scope.mutual.isFriend = true;
          });
        }

        // scope.unSearchFriend = function () {
        //   friendService.unSearchFriend(scope.mutual.id);
        // };

        // scope.reSearchFriend = function () {
        //   friendService.reSearchFriend(scope.mutual.id);
        // };
      }
    };
  }
]);
