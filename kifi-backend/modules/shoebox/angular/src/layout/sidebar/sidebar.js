'use strict';

angular.module('kifi')

.controller('SidebarCtrl', [
  'profileService', '$scope',
  function (profileService, $scope) {
    profileService.getMe().then(function () {
      $scope.showUserSidebar = profileService.userLoggedIn();
    });
  }
]);
