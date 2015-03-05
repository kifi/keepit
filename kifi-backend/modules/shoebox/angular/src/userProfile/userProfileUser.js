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
        scope.profile = scope.$parent.$parent.$parent.profile; // TODO: pass all the way down?
        scope.me = profileService.me;

        scope.showMutualConnections = function () {
          userProfileActionService.getMutualConnections(scope.user.id).then(function (data) {
            var person = _.assign(scope.user, 'id', 'username', 'pictureName');
            person.fullName = scope.user.firstName + ' ' + scope.user.lastName;
            person.numMutualFriends = scope.user.mConnections;
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
          } else if (scope.user.isFriend) {
            modalService.open({
              template: 'friends/unfriendConfirmModal.tpl.html',
              scope: scope
            });
          } else if (!scope.user.friendRequestSentAt) {
            var btnDuration = 600;  // easier to duplicate from stylesheet than to read from element
            var minimumDuration = $timeout(angular.noop, btnDuration / 2 + 160);  // added delay to avoid bouncing feeling
            scope.friendStatusChanging = true;
            inviteService.friendRequest(scope.user.id).then(function (data) {
              minimumDuration.then(function () {
                delete scope.user.friendRequestReceivedAt; // just to be sure, old server bug made it possible
                if (data.sentRequest) {
                  scope.user.friendRequestSentAt = Date.now();
                } else if (data.acceptedRequest || data.alreadyConnected) {
                  scope.user.isFriend = true;
                }
                scope.friendStatusChanging = false;
              });
            }, function () {
              scope.friendStatusChanging = false;
            });
          }
        };

        scope.accept = function () {
          friendService.acceptRequest(scope.user.id).then(function () {
            scope.user.isFriend = true;
            scope.user.friendRequestReceivedAt = null;
          });
        };

        scope.decline = function () {
          scope.user.friendRequestReceivedAt = null;
          friendService.ignoreRequest(scope.user.id);
        };

        scope.reallyUnfriend = function () {
          scope.user.isFriend = false;
          friendService.unfriend(scope.user.id).then(angular.noop, function error() {
            scope.user.isFriend = true;
          });
        };

        // scope.unSearchFriend = function () {
        //   friendService.unSearchFriend(scope.user.id);
        // };

        // scope.reSearchFriend = function () {
        //   friendService.reSearchFriend(scope.user.id);
        // };
      }
    };
  }
]);
