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

      //createOrganizationForSlackTeam: post(shoebox, '/organizations/create/slack?slackTeamId=:slackTeamId'),
      //    connectSlackTeamToOrganization: post(shoebox, '/organizations/:id/slack/connect?slackTeamId=:slackTeamId'),
    $scope.onClickedDone = function() {
      $window.location = 'https://kifi.com/site/organizations/' + $scope.selectedOrg.id
                            + '/slack/connect?slackTeamId=' + $scope.params.slackTeamId;
    };

    $scope.onClickedCreateTeam = function() {
      $window.location = 'https://www.kifi.com/site/organizations/create/slack?slackTeamId=' + $scope.params.slackTeamId;
    };

  }
]);
