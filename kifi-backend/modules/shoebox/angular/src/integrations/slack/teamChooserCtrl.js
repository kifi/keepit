'use strict';

angular.module('kifi')

.controller('SlackIntegrationTeamChooserCtrl', [
  '$window', '$scope', '$state', 'slackService', 'profileService', 'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function ($window, $scope, $state, slackService, profileService, ORG_PERMISSION, ORG_SETTING_VALUE) {
    $scope.ORG_PERMISSION = ORG_PERMISSION;
    $scope.ORG_SETTING_VALUE = ORG_SETTING_VALUE;
    $scope.me = profileService.me;
    $scope.params = $state.params;

    $scope.selectedOrg = null;
    $scope.orgs = [];


    slackService.getKifiOrgsForSlackIntegration().then(function(data) {
      $scope.orgs = data.orgs;
    });

    $scope.onClickedOrg = function(org) {
      // do something
      $scope.selectedOrg = org;
    };

    $scope.onClickedDone = function() {
      slackService.connectSlackTeamToOrganization($scope.selectedOrg.id, $scope.params.slackTeamId)
          .then(function(data) {
            if (data && data.redirectUrl) {
              $window.location = data.redirectUrl;
            }
          });
    };

    $scope.onClickedCreateTeam = function() {
      slackService.createOrganizationForSlackTeam($scope.params.slackTeamId)
          .then(function(data) {
            if (data && data.redirectUrl) {
              $window.location = data.redirectUrl;
            }
          });
    };

  }
]);
