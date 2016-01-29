'use strict';

angular.module('kifi')

.controller('SlackConfirmCtrl', [
  '$scope', 'installService', '$timeout', 'profile', '$stateParams',
  function ($scope, installService, $timeout, profile, $stateParams) {
    if (installService.installedVersion) {
      $scope.hasInstalled = true;
    } else {
      $scope.hasInstalled = false;
      $scope.canInstall = installService.canInstall;
    }
    $scope.platform = installService.getPlatformName();
    if ($stateParams.slackTeamId) {
      $scope.teamLink = 'https://www.kifi.com/s/' + $stateParams.slackTeamId + '/o/' + profile.organization.id;
    } else {
      $scope.teamLink = 'https://www.kifi.com/' + profile.organization.handle + '?signUpWithSlack';
    }
    $scope.profile = profile;

    $scope.showCopied =  function () {
      $scope.showCopiedConfirm = true;
      $timeout(function () {
        $scope.showCopiedConfirm = false;
      }, 3000);
    };
  }
]);
