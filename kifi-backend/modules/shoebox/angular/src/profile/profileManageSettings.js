'use strict';

angular.module('kifi')

.directive('kfProfileManageSettings', [
  'profileService',
  function (profileService) {
    return {
      restrict: 'A',
      scope: {},
      templateUrl: 'profile/profileManageSettings.tpl.html',
      link: function (scope) {
        scope.isOpen = false;
        scope.userProfileSettings = {};

        scope.toggleOpen = function () {
          scope.isOpen = !scope.isOpen;
        };

        function init() {
          profileService.getSettings().then(function (res) {
            scope.userProfileSettings = res.data;
            return;
          });
        }

        scope.$watch('userProfileSettings.showFollowedLibraries', function(newVal, oldVal) {
          if (_.isBoolean(newVal) && _.isBoolean(oldVal)) {
            profileService.setSettings(scope.userProfileSettings);
          }
        });

        init();

      }
    };
  }
]);
