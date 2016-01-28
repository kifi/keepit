'use strict';

angular.module('kifi')

.controller('IntegrationsCtrl', [
  '$scope', '$window', '$analytics', 'orgProfileService', 'messageTicker', 'libraryService', 'ORG_PERMISSION',
  function ($scope, $window, $analytics, orgProfileService, messageTicker, libraryService, ORG_PERMISSION) {

    $scope.canEditIntegrations =  ($scope.viewer.permissions.indexOf(ORG_PERMISSION.CREATE_SLACK_INTEGRATION) !== -1);
    $scope.integrations = [];

    var settings = $scope.profile && $scope.profile.config && $scope.profile.config.settings;
    var reactionSetting = settings && settings.slack_ingestion_reaction.setting;
    var notifSetting = settings && settings.slack_digest_notif.setting;
    $scope.slackIntegrationReactionModel = {enabled: reactionSetting === 'enabled'};
    $scope.slackIntegrationDigestModel = {enabled: notifSetting === 'enabled'};

    orgProfileService.getSlackIntegrationsForOrg($scope.profile)
    .then(function(res) {
      $scope.integrationsLoaded = true;
      $scope.slackTeam = res.slackTeam;

      res.libraries.forEach(function(lib) {
        var integrations = lib.slack.integrations;
        integrations.forEach(function(integration) {
          lib.library.sortName = lib.library.name.replace(/[^\w\s]|_/g, '').toLowerCase();
          $scope.integrations.push({
            library: lib.library,
            integration: integration,
            slackToKifi: integration.fromSlack && integration.fromSlack.status === 'on',
            slackToKifiMutable: integration.fromSlack && integration.fromSlack.isMutable,
            kifiToSlack: integration.toSlack && integration.toSlack.status === 'on',
            kifiToSlackMutable: integration.toSlack && integration.toSlack.isMutable
          });
        });
      });
      $scope.integrations.sort(function (a, b) {
        return a.library.sortName.localeCompare(b.library.sortName);
      });
    });

    $scope.onSlackIntegrationReactionChanged = function() {
      $scope.profile.config.settings.slack_ingestion_reaction.setting = $scope.slackIntegrationReactionModel.enabled ? 'enabled' : 'disabled';
      orgProfileService.setOrgSettings($scope.profile.id, { slack_ingestion_reaction: $scope.profile.config.settings.slack_ingestion_reaction.setting })
      .then(onSave, onError);
    };

    $scope.onSlackIntegrationDigestChanged = function() {
      $scope.profile.config.settings.slack_digest_notif.setting = $scope.slackIntegrationDigestModel.enabled ? 'enabled' : 'disabled';
      orgProfileService.setOrgSettings($scope.profile.id, { slack_digest_notif: $scope.profile.config.settings.slack_digest_notif.setting })
      .then(onSave, onError);
    };

    $scope.onKifiToSlackChanged = function(integration) {
      integration.integration.toSlack.status = integration.kifiToSlack ? 'on' : 'off';
      libraryService.modifySlackIntegrations(integration.library.id, [integration.integration.fromSlack, integration.integration.toSlack])
      .then(onSave, onError);
    };

    $scope.onSlackToKifiChanged = function(integration) {
      integration.integration.fromSlack.status = integration.slackToKifi ? 'on' : 'off';
      libraryService.modifySlackIntegrations(integration.library.id, [integration.integration.fromSlack, integration.integration.toSlack])
      .then(onSave, onError);
    };

    function onSave() {
      messageTicker({ text: 'Saved!', type: 'green' });
    }

    function onError() {
      messageTicker({ text: 'Odd, that didn’t work. Try again?', type: 'red' });
    }

    $scope.onClickedSyncAllSlackChannels = function() {
      var org = $scope.profile;
      $analytics.eventTrack('user_clicked_pane', { type: 'orgProfileIntegrations', action: 'syncAllChannels' });
      $window.location = '/site/organizations/' + org.id + '/slack/sync/public';
    };

    $scope.onClickedConnectSlack = function() {
      var org = $scope.profile;
      $analytics.eventTrack('user_clicked_pane', { type: 'orgProfileIntegrations', action: 'connectSlack' });
      $window.location = '/site/organizations/' + org.id + '/slack/connect';
    };
  }
]);
