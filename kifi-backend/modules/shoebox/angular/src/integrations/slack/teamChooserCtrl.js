'use strict';

angular.module('kifi')

.controller('SlackIntegrationTeamChooserCtrl', [
  '$window', '$scope', '$state', 'slackService', 'profileService', 'messageTicker',
  'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function ($window, $scope, $state, slackService, profileService, messageTicker,
            ORG_PERMISSION, ORG_SETTING_VALUE) {
    $scope.ORG_PERMISSION = ORG_PERMISSION;
    $scope.ORG_SETTING_VALUE = ORG_SETTING_VALUE;
    $scope.me = profileService.me;
    $scope.params = $state.params;

    $scope.selectedOrg = null;
    $scope.orgs = [];


    slackService
    .getKifiOrgsForSlackIntegration()
    .then(function(data) {
      $scope.orgs = data.orgs;
    });

    $scope.onClickedOrg = function(org) {
      $scope.selectedOrg = org;
    };

    $scope.onClickedDone = function() {
      slackService
      .connectTeam($scope.selectedOrg.id)
      .then(function (resp) {
        if (resp.redirect) {
          $window.location = resp.redirect;
        } else {
          // uh... how could this happen?
          messageTicker({
            text: 'Hmm, something went wrong : ( Try again?',
            type: 'red'
          });
          return;
        }
      });
    };

    $scope.onClickedCreateTeam = function() {
      $window.location = 'https://www.kifi.com/site/organizations/create/slack?slackTeamId=' + $scope.params.slackTeamId;
    };

  }
]);
