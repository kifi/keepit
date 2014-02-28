'use strict';

angular.module('kifi.layout.rightCol', ['kifi.modal'])

.controller('RightColCtrl', [
  '$scope', '$window', 'profileService', '$q',
  function ($scope, $window, profileService, $q) {
    $scope.data = {};

    // onboarding.js are using this functions
    $window.getMe = function() {
      return profileService.me ? $q.when(profileService.me) : profileService.fetchMe();
    };

    $window.exitOnboarding = function () {
      data.showGettingStarted = false;
      // $.postJson(xhrBase + '/user/prefs', {
      //   onboarding_seen: 'true'
      // }, function (data) {
      //   log('[prefs]', data);
      // });
      //initBookmarkImport();
    };

  }
]);
