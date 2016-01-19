'use strict';

angular.module('kifi')

.controller('IntegrationsCtrl', [
  '$scope', '$window', '$analytics', 'orgProfileService', 'messageTicker', 'libraryService', 'ORG_PERMISSION',
  function ($scope, $window, $analytics, orgProfileService, messageTicker, libraryService, ORG_PERMISSION) {

    $scope.canEditIntegrations =  ($scope.viewer.permissions.indexOf(ORG_PERMISSION.CREATE_SLACK_INTEGRATION) !== -1);
    $scope.integrations = [];
    orgProfileService.getSlackIntegrationsForOrg($scope.profile)
        .then(function(res) {
          res.libraries.forEach(function(lib) {
            if (lib.slack.integrations.length > 0) {
              var integrations = lib.slack.integrations;
              integrations.forEach(function(integration) {
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
            return a.library.name.toLowerCase().localeCompare(b.library.name.toLowerCase());
          });
        });


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
