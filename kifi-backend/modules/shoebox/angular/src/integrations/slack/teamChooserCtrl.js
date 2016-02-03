'use strict';

angular.module('kifi')

.controller('SlackIntegrationTeamChooserCtrl', [
  '$window', '$scope', '$state', 'slackService', 'profileService', 'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function ($window, $scope, $state, slackService, profileService, ORG_PERMISSION, ORG_SETTING_VALUE) {
    $scope.ORG_PERMISSION = ORG_PERMISSION;
    $scope.ORG_SETTING_VALUE = ORG_SETTING_VALUE;
    $scope.me = profileService.me;
    var params = $state.params;

    $scope.selectedOrg = null;
    $scope.orgs = [];


    slackService.getKifiOrgsForSlackIntegration().then(function(data) {
      $scope.orgs = data.orgs;
    });

    $scope.onClickedOrg = function(org) {
      $scope.selectedOrg = org;
    };

    $scope.onClickedDone = function() {
      slackService.connectTeam($scope.selectedOrg.id, params.slackState).then(function (resp) {
        if (resp.redirect) {
          $window.location = resp.redirect;
        } else {
          // uh... how could this happen?
          return;
        }
      });
    };

    $scope.onClickedCreateTeam = function() {
      slackService.createTeam(params.slackTeamId, params.slackState).then(function (resp) {
        if (resp.redirect) {
          $window.location = resp.redirect;
        } else {
          return;
        }
      });
    };

  }
]);
