'use strict';

angular.module('kifi')

.controller('PublicHeaderCtrl', ['$scope', '$rootScope', 'env', 'signupService', 'platformService', 'libraryService',
  function ($scope, $rootScope, env, signupService, platformService, libraryService) {
    $scope.navBase = env.navBase;

    $scope.clickLogo = function () {
      $scope.$emit('getCurrentLibrary', { callback: function (lib) {
        if (lib && lib.id) {
          libraryService.trackEvent('visitor_clicked_page', lib, {
            type: 'libraryLanding',
            action: 'clickedLogo'
          });
        }
      }});
    };

    $scope.join = function ($event) {
      if (platformService.isSupportedMobilePlatform()) {
        $event.preventDefault();
      }

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
    };

    $scope.login = function ($event) {
      if (platformService.isSupportedMobilePlatform()) {
        $event.preventDefault();
      }

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
    };
  }
]);
