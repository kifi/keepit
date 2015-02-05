'use strict';

angular.module('kifi')

.controller('LoggedOutHeaderCtrl', [
  '$scope', '$rootScope', '$state', 'env', 'signupService', 'platformService', 'libraryService', 'util',
  function ($scope, $rootScope, $state, env, signupService, platformService, libraryService, util) {
    $scope.navBase = env.navBase;

    $scope.clickLogo = function () {
      if (util.startsWith($state.current.name, 'library')) {
        $scope.$emit('getCurrentLibrary', { callback: function (lib) {
          if (lib && lib.id) {
            libraryService.trackEvent('visitor_clicked_page', lib, {
              type: 'libraryLanding',
              action: 'clickedLogo'
            });
          }
        }});
      } else if (util.startsWith($state.current.name, 'userProfile')) {
        $rootScope.$emit('trackUserProfileEvent', 'click', {
          action: 'clickedLogo'
        });
      }
    };

    $scope.join = function ($event) {
      $event.preventDefault();

      if (util.startsWith($state.current.name, 'library')) {
        $scope.$emit('getCurrentLibrary', {
          callback: function (lib) {
            var userData;

            if (lib && lib.id) {
              userData = { libraryId: lib.id };

              libraryService.trackEvent('visitor_clicked_page', lib, {
                type: 'libraryLanding',
                action: 'clickedSignupHeader'
              });
            }

            if (platformService.isSupportedMobilePlatform()) {
              platformService.goToAppOrStore();
            } else {
              signupService.register(userData);
            }
          }
        });
      } else if (util.startsWith($state.current.name, 'userProfile')) {
        $rootScope.$emit('trackUserProfileEvent', 'click', {
          action: 'clickedSignupHeader'
        });

        if (platformService.isSupportedMobilePlatform()) {
          platformService.goToAppOrStore();
        } else {
          signupService.register();
        }
      }
    };

    $scope.login = function ($event) {
      if (platformService.isSupportedMobilePlatform()) {
        $event.preventDefault();
      }

      if (util.startsWith($state.current.name, 'library')) {
        $scope.$emit('getCurrentLibrary', {
          callback: function (lib) {
            if (lib && lib.id) {
              libraryService.trackEvent('visitor_clicked_page', lib, {
                type: 'libraryLanding',
                action: 'clickedLoginHeader'
              });
            }

            if (platformService.isSupportedMobilePlatform()) {
              platformService.goToAppOrStore();
            }
          }
        });
      } else if (util.startsWith($state.current.name, 'userProfile')) {
        $rootScope.$emit('trackUserProfileEvent', 'click', {
          action: 'clickedLoginHeader'
        });

        if (platformService.isSupportedMobilePlatform()) {
          platformService.goToAppOrStore();
        }
      }
    };
  }
]);
