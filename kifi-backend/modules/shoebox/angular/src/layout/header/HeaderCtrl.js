'use strict';

angular.module('kifi.layout.header', ['kifi.layoutService', 'kifi.profileService'])

.controller('HeaderCtrl', [
  '$scope', 'layoutService', 'profileService',
  function ($scope, layoutService, profileService) {
    
    $scope.toggleMenu = function () {
      layoutService.toggleSidebar();
    };

    $scope.logout = function () {
      profileService.logout();
    };
  }
]);
