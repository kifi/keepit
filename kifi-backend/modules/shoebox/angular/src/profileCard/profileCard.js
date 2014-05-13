'use strict';

angular.module('kifi.profileCard', ['kifi.profileService'])

.directive('kfProfileCard', [
  'profileService',
  function (profileService) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'profileCard/profileCard.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;

        profileService.getMe();
      }
    };
  }
]);
