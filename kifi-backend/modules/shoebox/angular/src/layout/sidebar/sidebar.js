'use strict';

angular.module('kifi')

.controller('SidebarCtrl', [
  '$scope', 'profileService',
  function ($scope, profileService) {
    profileService.getMe().then(function () {
      $scope.showUserSidebar = profileService.userLoggedIn() === true;
    });
  }
]);
