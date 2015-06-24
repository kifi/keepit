'use strict';

angular.module('kifi')

.factory('profileWidget', [
  // '$analytics', '$http', '$location', '$q', '$timeout', 'env', 'Clutch', 'routeService',
  'profileService',
  function (profileService) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'profile/profileWidget.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;
      }
    };
  }
]);
