'use strict';

angular.module('kifi')

.controller('SlackConfirmCtrl', [
  '$scope', 'installService', '$timeout',
  function ($scope, installService, $timeout) {
    if (installService.installedVersion) {
      $scope.hasInstalled = true;
    } else {
      $scope.hasInstalled = false;
      $scope.canInstall = installService.canInstall;
    }
    $scope.platform = installService.getPlatformName();
    $scope.teamLink = 'https://www.kifi.com/kyfy/asf';

    $scope.showCopied =  function () {
      $scope.showCopiedConfirm = true;
      $timeout(function () {
        $scope.showCopiedConfirm = false;
      }, 3000);
    };
  }
]);
