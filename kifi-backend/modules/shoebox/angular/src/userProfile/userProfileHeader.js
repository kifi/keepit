'use strict';

angular.module('kifi')

.directive('kfUserProfileHeader', [
  '$rootScope', '$state', '$timeout', 'profileService', 'inviteService', 'friendService', '$document', 'modalService',
  function ($rootScope, $state, $timeout, profileService, inviteService, friendService, $document, modalService) {
    return {
      restrict: 'A',
      scope: {
        profile: '=',
        intent: '='
      },
      templateUrl: 'userProfile/userProfileHeader.tpl.html',
      link: function (scope, element) {
        var navLinks = angular.element();

        //
        // Internal Functions
        //

        function toggleFriendMenuOn() {
          scope.showFriendMenu = true;
          $document.on('mousedown', onClickFriendMenu);
        }
        function toggleFriendMenuOff() {
          scope.showFriendMenu = false;
          $document.off('mousedown', onClickFriendMenu);
        }

        function onClickFriendMenu(event) {
          var clickTarget = angular.element(event.target);
          if (scope.showFriendMenu && clickTarget.hasClass('kf-uph-action-menu-item')) {
            scope.unfriend();
            scope.$evalAsync(toggleFriendMenuOff);
          } else if (!clickTarget.hasClass('kf-uph-connect-image')) {
            scope.$evalAsync(toggleFriendMenuOff);
          }
        }

        function closeFriendRequestHeader(nextAnimation) {
          var header = angular.element('.kf-uph-connect-banner');
          header.animate({height: '0px'}, 150, nextAnimation);
        }

        function removeNavLinkHref(state) {
          navLinks.filter('[data-path=' + stateNamePart(state) + ']').removeAttr('href');
        }

        function restoreNavLinkHref(state) {
          navLinks.filter('[data-path=' + stateNamePart(state) + ']').prop('href', $state.href(state));
        }

        function stateNamePart(state) {
          return state && state.name.split('.')[1] || '""';
        }

        //
        // Scope Variables
        //
        scope.viewingOwnProfile = scope.profile.id === profileService.me.id;
        scope.isKifiCurator = ['kifi','kifi-editorial','kifi-eng'].indexOf(scope.profile.username) >= 0;
        scope.showFriendMenu = false;
        scope.connectionWithUser =
          scope.viewingOwnProfile ? 'self' :
          scope.profile.isFriend ? 'friends' :
          scope.profile.friendRequestSentAt ? 'request_sent' :
          scope.profile.friendRequestReceivedAt ? 'request_received' :
          $rootScope.userLoggedIn ? 'not_friends' : '';
        scope.showConnectCallout = scope.intent === 'connect' && scope.connectionWithUser === 'not_friends';

        //
        // Scope Functions
        //
        scope.trackClickedSettings = function () {
          $rootScope.$emit('trackUserProfileEvent', 'click', {action: 'clickedSettings'});
        };

        scope.toggleFriendMenu = function () {
          if (scope.showFriendMenu) {
            toggleFriendMenuOff();
          } else {
            toggleFriendMenuOn();
          }
        };

        scope.sendFriendRequest = function () {
          scope.showConnectCallout = false;
          $rootScope.$emit('trackUserProfileEvent', 'click', {action: 'clickedAddFriend'});

          var progressBar = angular.element('.kf-uph-progress-bar');
          var progressCheckmark = angular.element('.kf-uph-progress-check');

          var promise = inviteService.friendRequest(scope.profile.id);
          progressBar.animate({width: '15%'}, 80);

          promise.then(function(res) {
            if (res.sentRequest || res.acceptedRequest) {
              progressBar.animate({width: '100%'}, 200, function() {
                progressCheckmark.animate({opacity: 1}, 100, function() {
                  var connectBlock = angular.element('.kf-uph-connect');
                  var connectMsg = connectBlock.find('.kf-uph-action-text.connect');
                  var requestSentMsg = connectBlock.find('.kf-uph-action-text.hidden');

                  connectMsg.css('display', 'none');
                  requestSentMsg.animate({width: '113px'}, 350, function() { // size of Friend Request Sent message
                    requestSentMsg.animate({opacity: 1}, 200, function() {
                      scope.$evalAsync(function() {
                        scope.connectionWithUser = 'request_sent';
                      });
                    });
                  });
                });
              });
            }
          });
        };

        scope.acceptFriendRequest = function () {
          $rootScope.$emit('trackUserProfileEvent', 'click', {action: 'clickedAcceptFriend'});

          friendService.acceptRequest(scope.profile.id).then(function() {
            var friendsIcon = angular.element('.kf-uph-connect-image');
            var nextAnimation = function() {
              friendsIcon.animate({opacity: 1}, 300, function() {
                scope.$evalAsync(function() {
                  scope.connectionWithUser = 'friends';
                });
              });
            };
            closeFriendRequestHeader(nextAnimation);
          });
        };

        scope.ignoreFriendRequest = function () {
          $rootScope.$emit('trackUserProfileEvent', 'click', {action: 'clickedDeclineFriend'});

          friendService.ignoreRequest(scope.profile.id).then(function() {
            closeFriendRequestHeader();
            scope.connectionWithUser = 'not_friends';
          });
        };

        scope.unfriend = function () {
          friendService.unfriend(scope.profile.id).then(function() {
            scope.connectionWithUser = 'not_friends';
          });
        };

        scope.showBiographyModal = function () {
          modalService.open({
            template: 'profile/editUserBiographyModal.tpl.html',
            modalData: {
              biography: scope.profile.biography,
              onClose: function (newBiography) {
                scope.profile.biography = newBiography;
              }
            }
          });
        };

        //
        // Watches and listeners
        //

        scope.$on('$destroy', $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams, fromState) {
          restoreNavLinkHref(fromState);
          removeNavLinkHref(toState);
        }));

        $timeout(function () {
          navLinks = element.find('.kf-uph-nav-a');
          removeNavLinkHref($state.current);
        });
      }
    };
  }
]);
