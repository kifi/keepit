'use strict';

angular.module('kifi.layout.rightCol', ['kifi.modal'])

.controller('RightColCtrl', [
  '$scope', '$window', 'profileService', '$q', '$http', 'env',
  function ($scope, $window, profileService, $q, $http, env) {
    $scope.data = {};

    // onboarding.js are using this functions
    $window.getMe = function() {
      return profileService.me ? $q.when(profileService.me) : profileService.fetchMe();
    };

    $window.exitOnboarding = function () {
      $scope.data.showGettingStarted = false;
      $http.post(env.xhrBase + '/user/prefs', {
        onboarding_seen: 'true'
      });
      $scope.$apply();
      //initBookmarkImport();
    };

  }
]);
