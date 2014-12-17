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
        scope.settings = {};

        scope.toggle = function () {
          scope.isOpen = !scope.isOpen;
        };

        function init() {
          profileService.getSettings().then(function (val) {
            scope.settings = val;
            return scope.settings;
          });
        }

        scope.$watch(function() {
          return scope.settings.showFollowedLibraries;
        }, function(newVal, oldVal) {
          if (_.isBoolean(newVal) && _.isBoolean(oldVal)) {
            profileService.setSettings(scope.settings);
          }
        });

        init();

      }
    };
  }
]);
