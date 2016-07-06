'use strict';

angular.module('kifi')

.directive('kfUserCard', [
  '$timeout', 'env', '$filter', 'modalService', 'profileService',
  'friendService', 'inviteService', 'platformService',
  function (
      $timeout, env, $filter, modalService, profileService,
      friendService, inviteService, platformService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        user: '=kfUserCard',
        mutualUserInfo: '='
      },
      templateUrl: 'userCard/userCard.tpl.html',
      link: function (scope, element) {
        if (scope.$root.userLoggedIn) {
          scope.isSelf = profileService.me.id === scope.user.id;

          // unpacking information about relationship, since it's (confusingly) not always about the user on this card
          scope.mutual = _.pick(scope.mutualUserInfo || scope.user, 'id', 'firstName', 'lastName', 'pictureName', 'username');
          scope.mutual.connections = scope.user.mConnections;
          scope.mutual.libraries = scope.user.mLibraries;
          _.assign(scope.mutual, _.pick(scope.user, 'isFriend', 'friendRequestSentAt', 'friendRequestReceivedAt', 'unsearched'));
        }

        scope.showMutualConnections = function () {
          friendService.getMutualConnections(scope.mutual.id).then(function (data) {
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
          if (platformService.isSupportedMobilePlatform()) {
            platformService.goToAppOrStore(env.navBase + $filter('profileUrl')(scope.user));
          } else if (scope.connectBtnChanging) {
            return; // ignore
          } else if (!scope.$root.userLoggedIn) {
            scope.asd = 'asd';
          } else if (scope.mutual.isFriend) {
            scope.unfriend();
          } else if (!scope.mutual.friendRequestSentAt) {
            var btnDuration = 600;  // easier to duplicate from stylesheet than to read from element
            var minimumDuration = $timeout(angular.noop, btnDuration + 160);  // added delay to avoid bouncing feeling
            scope.connectBtnChanging = true;
            var onConnectBtnChangeEnd = function () {
              scope.connectBtnChanging = false;
              scope.connectBtnChanged = true;
              $timeout(function () {
                scope.connectBtnChanged = false;
              }, btnDuration);
            };
            inviteService.friendRequest(scope.mutual.id).then(function (data) {
              minimumDuration.then(function () {
                delete scope.mutual.friendRequestReceivedAt; // just to be sure, old server bug made it possible
                if (data.sentRequest) {
                  scope.mutual.friendRequestSentAt = Date.now();
                } else if (data.acceptedRequest || data.alreadyConnected) {
                  scope.mutual.isFriend = true;
                }
                onConnectBtnChangeEnd();
              });
            }, onConnectBtnChangeEnd);
          }

        };

        scope.accept = function () {
          friendService.acceptRequest(scope.mutual.id).then(function () {
            scope.mutual.isFriend = true;
            scope.mutual.friendRequestReceivedAt = null;
            hideOverflowTemporarily();
          });
        };

        scope.decline = function () {
          scope.mutual.friendRequestReceivedAt = null;
          friendService.ignoreRequest(scope.mutual.id);
          hideOverflowTemporarily();
        };

        scope.unfriend = function () {
          modalService.open({
            template: 'friends/unfriendConfirmModal.tpl.html',
            scope: _.assign(scope.$root.$new(), {
              friend: scope.mutual,
              reallyUnfriend: function () {
                scope.mutual.isFriend = false;
                friendService.unfriend(scope.mutual.id).then(angular.noop, function error() {
                  scope.mutual.isFriend = true;
                });
              }
            })
          });
        };

        scope.toggleSearch = function () {
          var unsearched = scope.mutual.unsearched;
          friendService[unsearched ? 'reSearchFriend' : 'unSearchFriend'](scope.mutual.id).then(function () {
            scope.mutual.unsearched = !unsearched;
          });
        };

        function hideOverflowTemporarily() {
          element.addClass('kf-clipped');
          $timeout(function () {
            element.removeClass('kf-clipped');
          }, 500);
        }
      }
    };
  }
]);
