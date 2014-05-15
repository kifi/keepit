'use strict';

angular.module('kifi.layout.header', ['kifi.modal'])

.controller('HeaderCtrl', [
  '$scope', 'profileService',
  function ($scope, profileService) {
    
    $scope.logout = function () {
      profileService.logout();
    };
  }
]);
