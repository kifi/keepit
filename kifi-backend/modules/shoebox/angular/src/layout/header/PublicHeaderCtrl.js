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

    $scope.onSearchIconClick = function () {
      $rootScope.$emit('loggedOutLibrarySearch');
    };

    $scope.join = function ($event) {
      $event.preventDefault();

      if (platformService.isSupportedMobilePlatform()) {
        platformService.goToAppOrStore();
      } else {
        $scope.$emit('getCurrentLibrary', { callback: function (lib) {
          var userData;

          if (lib && lib.id) {
            userData = { libraryId: lib.id };

            libraryService.trackEvent('visitor_clicked_page', lib, {
              type: 'libraryLanding',
              action: 'clickedSignupHeader'
            });
          }

          signupService.register(userData);
        }});
      }
    };

    $scope.login = function ($event) {
      if (platformService.isSupportedMobilePlatform()) {
        $event.preventDefault();
        platformService.goToAppOrStore();
      } else {
        $scope.$emit('getCurrentLibrary', { callback: function (lib) {
          if (lib && lib.id) {
            libraryService.trackEvent('visitor_clicked_page', lib, {
              type: 'libraryLanding',
              action: 'clickedLoginHeader'
            });
          }
        }});
      }
    };
  }
]);
