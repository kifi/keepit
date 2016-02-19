'use strict';

angular.module('kifi')

.controller('SlackConfirmCtrl', [
  '$rootScope', '$scope', 'installService', '$timeout', 'profile', '$stateParams', 'messageTicker',
  function ($rootScope, $scope, installService, $timeout, profile, $stateParams, messageTicker) {

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

    $scope.installExt = function () {
      $rootScope.$emit('trackOrgProfileEvent', 'click', {
        type: 'org_profile:settings:integration_confirmation_slack',
        action: 'clicked_install_extension'
      });
      installService.triggerInstall(function onError() {
        messageTicker({ text: 'Oops, that didn’t work. Contact support if the problem persists.', type: 'red' });
      });
    };

    $scope.clickedCopy =  function () {
      $rootScope.$emit('trackOrgProfileEvent', 'click', {
        type: 'org_profile:settings:integration_confirmation_slack',
        action: 'clicked_copy_invite_url'
      });

      $scope.showCopiedConfirm = true;
      $timeout(function () {
        $scope.showCopiedConfirm = false;
      }, 3000);
    };

    $timeout(function () {
      $rootScope.$emit('trackOrgProfileEvent', 'view', {
        type: 'org_profile:settings:integration_confirmation_slack',
        extensionInstalled: !!installService.installedVersion
      });
    }, 1000);
  }
]);
