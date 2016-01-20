'use strict';

angular.module('kifi')

.controller('IntegrationsCtrl', [
  '$scope', '$window', '$analytics', 'orgProfileService', 'messageTicker', 'libraryService', 'ORG_PERMISSION',
  function ($scope, $window, $analytics, orgProfileService, messageTicker, libraryService, ORG_PERMISSION) {

    $scope.canEditIntegrations =  ($scope.viewer.permissions.indexOf(ORG_PERMISSION.CREATE_SLACK_INTEGRATION) !== -1);
    $scope.integrations = [];
    $scope.slackIntegrationReactionModel = {enabled: $scope.profile.config.settings.slack_ingestion_reaction.setting === 'enabled'};
    orgProfileService.getSlackIntegrationsForOrg($scope.profile)
        .then(function(res) {
          res.libraries.forEach(function(lib) {
            if (lib.slack.integrations.length > 0) {
              var integrations = lib.slack.integrations;
              integrations.forEach(function(integration) {
                lib.library.sortName = lib.library.name.replace(/[^\w\s]|_/g, '').toLowerCase();
                $scope.integrations.push({
                  library: lib.library,
                  integration: integration,
                  slackToKifi: integration.fromSlack && integration.fromSlack.status === 'on',
                  kifiToSlack: integration.toSlack && integration.toSlack.status === 'on'
                });
              });
            }
          });
          $scope.integrations.sort(function (a, b) {
            return a.library.sortName.localeCompare(b.library.sortName);
          });
        });


    $scope.onSlackIntegrationReactionChanged = function() {
        $scope.profile.config.settings.slack_ingestion_reaction.setting = $scope.slackIntegrationReactionModel.enabled ? 'enabled' : 'disabled';
        orgProfileService.setOrgSettings($scope.profile.id, { slack_ingestion_reaction: $scope.profile.config.settings.slack_ingestion_reaction.setting });

    };

    $scope.onKifiToSlackChanged = function(integration) {
        integration.integration.toSlack.status = integration.kifiToSlack ? 'on' : 'off';
        libraryService.modifySlackIntegrations(integration.library.id, [integration.integration.fromSlack, integration.integration.toSlack]);
    };

    $scope.onSlackToKifiChanged = function(integration) {
        integration.integration.fromSlack.status = integration.slackToKifi ? 'on' : 'off';
        libraryService.modifySlackIntegrations(integration.library.id, [integration.integration.fromSlack, integration.integration.toSlack]);
    };


    $scope.onClickedSyncAllSlackChannels = function() {
      var org = $scope.profile;
      $analytics.eventTrack('user_clicked_pane', { type: 'orgProfileSlackUpsell', action: 'syncAllChannels' });
      if (org && org.slack && org.slack.link) {
        $window.location = org.slack.link;
      } else {
        messageTicker({
          text: 'Unable to retrieve Team information, please refresh and try again.',
          type: 'red'
        });
      }
    };
  }
]);
