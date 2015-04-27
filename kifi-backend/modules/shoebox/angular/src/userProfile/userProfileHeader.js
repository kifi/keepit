'use strict';

angular.module('kifi')

.directive('kfUserProfileHeader', [
  '$rootScope', '$state', '$timeout', 'profileService', 'inviteService', 'friendService', '$document', 'modalService', 'util',
  function ($rootScope, $state, $timeout, profileService, inviteService, friendService, $document, modalService, util) {
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

        function removeNavLinkHref(state) {
          navLinks.filter('[data-path=' + stateNamePart(state) + ']').removeAttr('href');
        }

        function restoreNavLinkHref(state) {
          navLinks.filter('[data-path=' + stateNamePart(state) + ']').prop('href', $state.href(state));
        }

        function stateNamePart(state) {
          return state && state.name.split('.')[1] || '""';
        }

        function onWinMouseDownStopSharing(e) {
          if (!angular.element(e.target).is('.kf-uph-share,.kf-uph-share *,.kf-uph-share-url')) {
            scope.$apply(angular.bind(scope, scope.toggleSharing, false));
          }
        }

        function onWinKeyDownStopSharing(e) {
          if (e.keyCode === 9 || e.keyCode === 27) {  // tab or esc
            scope.$apply(angular.bind(scope, scope.toggleSharing, false));
          }
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
        scope.sharing = false;

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

          friendService.acceptRequest(scope.profile.id).then(function () {
            scope.connectionWithUser = 'friends';
          });
        };

        scope.ignoreFriendRequest = function () {
          $rootScope.$emit('trackUserProfileEvent', 'click', {action: 'clickedDeclineFriend'});

          friendService.ignoreRequest(scope.profile.id).then(function() {
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

        scope.toggleSharing = function (arg) {
          var sharing = scope.sharing = typeof arg === 'boolean' ? arg : !scope.sharing;
          if (sharing) {
            $timeout(function () {
              var el = element.find('.kf-uph-share-url')[0];
              var r = document.createRange();
              r.selectNodeContents(el);
              var sel = window.getSelection();
              sel.removeAllRanges();
              sel.addRange(r);
              $rootScope.$emit('trackUserProfileEvent', 'click', {action: 'clickedShare'});
              if (typeof arg === 'object' && arg.pageX && arg.currentTarget === document.activeElement) {  // button clicked, and not via a key press
                arg.currentTarget.blur();
              }
            });
          }
          angular.element(window)
          [sharing ? 'on' : 'off']('mousedown', onWinMouseDownStopSharing)
          [sharing ? 'on' : 'off']('keydown', onWinKeyDownStopSharing);
        };

        //
        // Watches and listeners
        //

        scope.$watch('profile.biography', function (bio) {
          scope.bioHtml = util.linkify(bio || '');
        });

        scope.$on('$destroy', $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams, fromState) {
          restoreNavLinkHref(fromState);
          removeNavLinkHref(toState);
        }));

        scope.$on('$destroy', function () {
          angular.element(window)
          .off('mousedown', onWinMouseDownStopSharing)
          .off('keydown', onWinKeyDownStopSharing);
        });

        $timeout(function () {
          navLinks = element.find('.kf-uph-nav-a');
          removeNavLinkHref($state.current);
        });
      }
    };
  }
]);
