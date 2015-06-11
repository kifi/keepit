'use strict';

angular.module('kifi')

.controller('GetTheAppModalCtrl', [
  '$scope', 'platformService', 'profileService',
  function ($scope, platformService, profileService) {

    $scope.platformName = null;
    if (platformService.isIPhone()) {
      $scope.platformName = 'iOS';
    } else if (platformService.isAndroid()) {
      $scope.platformName = 'Android';
    }

    $scope.goToAppOrStore = platformService.goToAppOrStore;

    $scope.name = profileService.me && profileService.me.firstName;


  }
]);
