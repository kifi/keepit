'use strict';

angular.module('kifi')

.controller('SlackConfirmCtrl', [
  '$scope', 'installService', '$timeout', 'profile',
  function ($scope, installService, $timeout, profile) {
    if (installService.installedVersion) {
      $scope.hasInstalled = true;
    } else {
      $scope.hasInstalled = false;
      $scope.canInstall = installService.canInstall;
    }
    $scope.platform = installService.getPlatformName();
    $scope.teamLink = 'https://www.kifi.com/kyfy/asf';
    $scope.profile = profile;

    $scope.showCopied =  function () {
      $scope.showCopiedConfirm = true;
      $timeout(function () {
        $scope.showCopiedConfirm = false;
      }, 3000);
    };
  }
]);
