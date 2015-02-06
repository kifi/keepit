'use strict';

angular.module('kifi')

.directive('kfProfileManageSettings', [
  'profileService', 'modalService',
  function (profileService, modalService) {
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

        scope.saveSettings = function () {
          profileService.setSettings(scope.userProfileSettings);
        };

        scope.openPersonaModal = function () {
          modalService.open({
            template: 'persona/managePersonaModal.tpl.html'
          });
        };

        profileService.getSettings().then(function (res) {
          scope.userProfileSettings = res.data;
        });
      }
    };
  }
]);
