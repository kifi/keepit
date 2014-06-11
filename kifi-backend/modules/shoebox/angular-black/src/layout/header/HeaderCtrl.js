'use strict';

angular.module('kifi.layout.header', ['kifi.profileService'])

.controller('HeaderCtrl', [
  '$scope', '$rootElement', 'profileService', '$location', 'util',
  function ($scope, $rootElement, profileService, $location, util) {

    $scope.toggleMenu = function () {
      $rootElement.toggleClass('kf-sidebar-active');
    };

    $scope.me = profileService.me;
    profileService.getMe();

    $scope.isActive = function (path) {
      var loc = $location.path();
      return loc === path || util.startsWith(loc, path + '/');
    };

    $scope.logout = function () {
      profileService.logout();
    };
  }
]);
