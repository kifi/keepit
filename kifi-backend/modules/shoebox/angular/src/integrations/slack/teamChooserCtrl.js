'use strict';

angular.module('kifi')

.controller('SlackIntegrationTeamChooserCtrl', [
  '$scope', '$state', 'slackService', 'profileService', 'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function ($scope, $state, slackService, profileService, ORG_PERMISSION, ORG_SETTING_VALUE) {
    $scope.ORG_PERMISSION = ORG_PERMISSION;
    $scope.ORG_SETTING_VALUE = ORG_SETTING_VALUE;
    $scope.me = profileService.me;
    $scope.params = $state.params;

    $scope.selectedOrg = null;
    $scope.orgs = function () {
      return $scope.me.orgs;
    };

    $scope.onClickedOrg = function(org) {
      // do something
      $scope.selectedOrg = org;
    };

    $scope.onClickedDone = function() {
      slackService.createOrganizationForSlackTeam($scope.params.slackTeamId)
          .then(function() {

          });
    };

    $scope.onClickedCreateTeam = function() {
      slackService.connectSlackTeamToOrganization($scope.selectedOrg.id, $scope.params.slackTeamId)
          .then(function() {

          });
    };

  }
]);
