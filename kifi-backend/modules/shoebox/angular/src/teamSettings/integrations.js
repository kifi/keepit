'use strict';

angular.module('kifi')

.controller('IntegrationsCtrl', [
  '$scope', '$window', '$analytics', 'orgProfileService', 'messageTicker', 'libraryService', 'ORG_PERMISSION',
  'slackService', 'profileService', 'profile',
  function ($scope, $window, $analytics, orgProfileService, messageTicker, libraryService, ORG_PERMISSION, slackService, profileService, profile) {

    $scope.canEditIntegrations =  ($scope.viewer.permissions.indexOf(ORG_PERMISSION.CREATE_SLACK_INTEGRATION) !== -1);
    $scope.integrations = [];

    var settings = profile.organization && profile.organization.config && profile.organization.config.settings;
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

    $scope.$emit('trackOrgProfileEvent', 'view', { type: 'org_profile:settings:integrations' });

    $scope.onSlackIntegrationReactionChanged = function() {
      profile.organization.config.settings.slack_ingestion_reaction.setting = $scope.slackIntegrationReactionModel.enabled ? 'enabled' : 'disabled';
      orgProfileService.setOrgSettings(profile.organization.id,
        { slack_ingestion_reaction: profile.organization.config.settings.slack_ingestion_reaction.setting })
      .then(onSave, onError);
    };

    $scope.onSlackIntegrationDigestChanged = function() {
      profile.organization.config.settings.slack_digest_notif.setting = $scope.slackIntegrationDigestModel.enabled ? 'enabled' : 'disabled';
      orgProfileService.setOrgSettings(profile.organization.id, { slack_digest_notif: profile.organization.config.settings.slack_digest_notif.setting })
      .then(onSave, onError);
    };

    $scope.onKifiToSlackChanged = function(integration) {
      integration.integration.toSlack.status = integration.kifiToSlack ? 'on' : 'off';
      slackService.modifyLibraryPushIntegration(integration.library.id, integration.integration.toSlack.id, integration.kifiToSlack)
      .then(onSave, onError);
    };

    $scope.onSlackToKifiChanged = function(integration) {
      integration.integration.fromSlack.status = integration.slackToKifi ? 'on' : 'off';
      slackService.modifyLibraryIngestIntegration(integration.library.id, integration.integration.fromSlack.id, integration.slackToKifi)
      .then(onSave, onError);
    };

    $scope.isAdmin = ((profileService.me.experiments || []).indexOf('admin') !== -1);

    var existingBlacklist = (settings.slack_ingestion_domain_blacklist || {}).setting || [];
    $scope.blacklist = {
      newPath: '',
      existing: existingBlacklist,
      editable: !!(settings.slack_ingestion_domain_blacklist || {}).editable,
      limit: existingBlacklist.length > 6 ? 4 : 6
    };

    $scope.expandBlacklist = function () {
      $scope.blacklist.limit += 50;
    };

    $scope.removeBlacklistEntry = function (path) {
      _.remove($scope.blacklist.existing, {path: path});
      orgProfileService.setOrgSettings(profile.organization.id, { slack_ingestion_domain_blacklist: $scope.blacklist.existing })
      .then(function (data) {
        $scope.blacklist.existing = data.settings.slack_ingestion_domain_blacklist.setting;
      }, onError);
    };

    $scope.addBlacklistEntry = function () {
      var path = $scope.blacklist.newPath.replace(/^https?:\/\//,'').trim();
      if (path.length > 50) {
        return;
      }
      $scope.blacklist.existing.splice($scope.blacklist.limit, 0, {
        path: path,
        createdAt: +new Date()
      });
      $scope.blacklist.limit += 1;
      orgProfileService.setOrgSettings(profile.organization.id, { slack_ingestion_domain_blacklist: $scope.blacklist.existing })
      .then(function (data) {
        $scope.blacklist.existing = data.settings.slack_ingestion_domain_blacklist.setting;
      }, onError);
      $scope.blacklist.newPath = '';
    };

    function onSave(resp) {
      if (!resp || resp.success) {
        messageTicker({ text: 'Saved!', type: 'green' });
      } else if (resp.redirect) {
        $window.location = resp.redirect;
      }
    }

    function onError() {
      messageTicker({ text: 'Odd, that didn’t work. Try again?', type: 'red' });
    }

    $scope.onClickedSyncAllSlackChannels = function() {
      $analytics.eventTrack('user_clicked_page', { type: 'orgProfileIntegrations', action: 'syncAllChannels' });
      slackService.publicSync(profile.organization.id).then(function (resp) {
        if (resp.success) {
          messageTicker({ text: 'Syncing!', type: 'green' });
        } else if (resp.redirect) {
          $window.location = resp.redirect;
        }
      });
    };

    $scope.onClickedConnectSlack = function() {
      $analytics.eventTrack('user_clicked_page', { type: 'orgProfileIntegrations', action: 'connectSlack' });
      slackService.connectTeam(profile.organization.id).then(function (resp) {
        if (resp.success) {
          messageTicker({ text: 'Slack connected!', type: 'green' });
        } else if (resp.redirect) {
          $window.location = resp.redirect;
        }
      });

    };
  }
]);
