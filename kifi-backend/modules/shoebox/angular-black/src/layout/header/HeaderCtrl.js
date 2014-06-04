'use strict';

angular.module('kifi.layout.header', ['kifi.profileService'])

.controller('HeaderCtrl', [
  '$scope', '$rootElement', 'profileService',
  function ($scope, $rootElement, profileService) {
    
    $scope.toggleMenu = function () {
      $rootElement.toggleClass('kf-sidebar-active');
    };

    $scope.me = profileService.me;
    profileService.getMe();

    $scope.logout = function () {
      profileService.logout();
    };
  }
]);
