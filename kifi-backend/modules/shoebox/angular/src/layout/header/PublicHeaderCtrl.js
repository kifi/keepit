'use strict';

angular.module('kifi')

.controller('PublicHeaderCtrl', ['$scope', 'env', 'signupService', 'platformService',
  function ($scope, env, signupService, platformService) {
    $scope.navBase = env.navBase;

    $scope.join = function ($event) {
      $event.preventDefault();

      if (platformService.isSupportedMobilePlatform()) {
        platformService.goToAppOrStore();
      } else {
        $scope.$emit('getCurrentLibrary', { callback: function (lib) {
          var userData;
          if (lib && lib.id) {
            userData = { libraryId: lib.id };
          }
          signupService.register(userData);
        }});
      }
    };

    $scope.login = function ($event) {
      if (platformService.isSupportedMobilePlatform()) {
        $event.preventDefault();
        platformService.goToAppOrStore();
      }
    };
  }
]);
