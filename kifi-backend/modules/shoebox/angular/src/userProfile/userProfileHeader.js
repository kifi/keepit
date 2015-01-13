'use strict';

angular.module('kifi')

.directive('kfUserProfileHeader', ['$rootScope', 'profileService', 'inviteService', 'friendService', '$document',
  function ($rootScope, profileService, inviteService, friendService, $document) {
    return {
      //replace: true,
      restrict: 'A',
      scope: {
        profile: '='
      },
      templateUrl: 'userProfile/userProfileHeader.tpl.html',
      link: function (scope /*, element, attrs*/) {

        //
        // Configs.
        //
        var userNavLinksConfig = [
          { name: 'Libraries', /*routeState: 'userProfile.libraries.own',*/ countFieldName: 'numLibraries' },  // routeState is V2
          { name: 'Keeps', countFieldName: 'numKeeps' }  // V1 only

          /*
           * V2
           */
          // { name: 'Friends', routeState: 'userProfile.friends', countFieldName: 'numFriends' },
          // { name: 'Followers', routeState: 'userProfile.followers', countFieldName: 'numFollowers' },
          // { name: 'Helped', routeState: 'userProfile.helped', countFieldName: 'numRekeeps' }
        ];

        var kifiCuratorUsernames = [
          'kifi',
          'kifi-editorial',
          'kifi-eng'
        ];

        //
        // Internal Functions
        //
        function isKifiCurator(username) {
          return kifiCuratorUsernames.indexOf(username) !== -1;
        }

        function initViewingUserStatus() {
          scope.userLoggedIn = $rootScope.userLoggedIn;
          scope.viewingOwnProfile = scope.profile.id === profileService.me.id;

          if (scope.userLoggedIn) {
            if (scope.viewingOwnProfile) {
              scope.connectionWithUser = 'self';
            } else {
              if (scope.profile.isFriend) {
                scope.connectionWithUser = 'friends';
              } else if (scope.profile.friendRequestSentAt) {
                scope.connectionWithUser = 'request_sent';
              } else if (scope.profile.friendRequestReceivedAt) {
                scope.connectionWithUser = 'request_received';
              } else {
                scope.connectionWithUser = 'not_friends';
              }
            }
          }
        }

        function initUserNavLinks() {
          scope.userNavLinks = _.map(userNavLinksConfig, function (config) {
            return {
              name: config.name,
              count: scope.profile[config.countFieldName],
              routeState: config.routeState
            };
          });
        }

        function toggleFriendMenuOn() {
          scope.showFriendMenu = true;
          $document.on('mousedown', onClickFriendMenu);
        }
        function toggleFriendMenuOff() {
          scope.showFriendMenu = false;
          $document.off('mousedown', onClickFriendMenu);
        }

        function toggleRespondMenuOn() {
          scope.showRespondMenu = true;
          $document.on('mousedown', onClickRespondMenu);
        }
        function toggleRespondMenuOff() {
          scope.showRespondMenu = false;
          $document.off('mousedown', onClickRespondMenu);
        }

        function onClickFriendMenu(event) {
          var clickTarget = angular.element(event.target);
          if (scope.showFriendMenu && clickTarget.is('.kf-user-profile-action-menu .kf-user-profile-action-selection')) {
            scope.unfriend();
            scope.$evalAsync(toggleFriendMenuOff);
          } else if (!clickTarget.is('.kf-user-profile-connect-image')) {
            scope.$evalAsync(toggleFriendMenuOff);
          }
        }

        function onClickRespondMenu(event) {
          var clickTarget = angular.element(event.target);
          var acceptClass = '.kf-user-profile-action-menu .kf-user-profile-action-selection.accept';
          var declineClass = '.kf-user-profile-action-menu .kf-user-profile-action-selection.decline';

          if (scope.showRespondMenu && clickTarget.is(acceptClass)) {
            scope.acceptFriendRequest();
            scope.$evalAsync(toggleRespondMenuOff);
          } else if (scope.showRespondMenu && clickTarget.is(declineClass)) {
            scope.ignoreFriendRequest();
            scope.$evalAsync(toggleRespondMenuOff);
          } else if (!(clickTarget.is('.kf-user-profile-connect-image') ||
                        clickTarget.is('.kf-user-profile-action') ||
                        clickTarget.is('.kf-user-profile-connect'))) {
            scope.$evalAsync(toggleRespondMenuOff);
          }
        }


        //
        // Scope Variables
        //
        scope.userLoggedIn = false;
        scope.viewingOwnProfile = false;
        scope.isKifiCurator = false;
        scope.userNavLinks = [];

        scope.showRespondMenu = false;
        scope.showFriendMenu = false;

        // for user connection, potential (states -> actions):
        //   'self' -> setting
        //   'not_friends' -> send request
        //   'request_sent' -> (nothing)
        //   'request_received' -> accept request
        //   'friends' -> defriend
        scope.connectionWithUser = '';

        //
        // Scope Functions
        //
        scope.toggleFriendMenu = function() {
          if (scope.showFriendMenu) {
            toggleFriendMenuOff();
          } else {
            toggleFriendMenuOn();
          }
        };

        scope.toggleRespondMenu = function() {
          if (scope.showRespondMenu) {
            toggleRespondMenuOff();
          } else {
            toggleRespondMenuOn();
          }
        };

        scope.sendFriendRequest = function() {
          var progressBar = angular.element('.kf-user-profile-progress-bar');
          var progressCheckmark = angular.element('.kf-user-profile-progress-check');

          var promise = inviteService.friendRequest(scope.profile.id);
          progressBar.animate({width: '15%'}, 80);

          promise.then(function(res) {
            if (res.sentRequest || res.acceptedRequest) {
              progressBar.animate({width: '100%'}, 200, function() {
                progressCheckmark.animate({opacity: 1}, 150, function() {
                  var connectBlock = angular.element('.kf-user-profile-connect');
                  var connectMsg = connectBlock.find('.kf-user-profile-action.connect');
                  var requestSentMsg = connectBlock.find('.kf-user-profile-action.hidden');

                  connectMsg.css('display', 'none');
                  requestSentMsg.animate({width: '113px'}, 80, function() { // size of Friend Request Sent message
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

        scope.acceptFriendRequest = function() {
          friendService.acceptRequest(scope.profile.id).then(function() {
            scope.connectionWithUser = 'friends';
          });
        };

        scope.ignoreFriendRequest = function() {
          friendService.ignoreRequest(scope.profile.id).then(function() {
            scope.connectionWithUser = 'not_friends';
          });
        };

        scope.unfriend = function() {
          friendService.unfriend(scope.profile.id).then(function() {
            scope.connectionWithUser = 'not_friends';
          });
        };


        //
        // Watchers & Listeners
        //
        scope.$watch('profile', function (newProfile) {
          if (newProfile) {
            scope.isKifiCurator = isKifiCurator(scope.profile.username);
            initViewingUserStatus();
            initUserNavLinks();
          }
        });
      }
    };
  }
]);
