'use strict';

angular.module('kifi')

.directive('kfProfileLhrLibrarySorting', [
  'profileService',
  function (profileService) {
    return {
      restrict: 'A',
      scope: {},
      templateUrl: 'profile/profileLhrLibrarySorting.tpl.html',
      link: function (scope) {
        scope.isOpen = false;
        scope.userProfileSettings = {};

        scope.toggleOpen = function () {
          scope.isOpen = !scope.isOpen;
        };

        scope.saveSettings = function () {
          profileService.setSettings(scope.userProfileSettings);
        };

        profileService.getSettings().then(function (res) {
          scope.userProfileSettings = res.data;
        });
      }
    };
  }
]);
