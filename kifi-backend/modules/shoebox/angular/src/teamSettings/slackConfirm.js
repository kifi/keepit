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
    var teamIdParam = '';
    if ($stateParams.slackTeamId) {
      teamIdParam = '&slackTeamId=' + $stateParams.slackTeamId;
    }
    $scope.teamLink = 'https://www.kifi.com/' + profile.organization.handle + '?signUpWithSlack' + teamIdParam;
    $scope.profile = profile;

    $scope.showCopied =  function () {
      $scope.showCopiedConfirm = true;
      $timeout(function () {
        $scope.showCopiedConfirm = false;
      }, 3000);
    };
  }
]);
