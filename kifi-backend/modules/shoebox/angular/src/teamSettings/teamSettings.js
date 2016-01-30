'use strict';

angular.module('kifi')

.controller('TeamSettingsCtrl', [
  '$window', '$rootScope', '$scope', '$state', '$analytics', '$timeout', 'billingState',
  'orgProfileService', 'profileService', 'billingService', 'messageTicker',
  'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function ($window, $rootScope, $scope, $state, $analytics, $timeout, billingState,
            orgProfileService, profileService, billingService, messageTicker,
            ORG_PERMISSION, ORG_SETTING_VALUE) {
    $scope.ORG_PERMISSION = ORG_PERMISSION;
    $scope.settingsSectionTemplateData = [
      {
        heading: 'Team Profile',
        fields: [
          {
            title: 'Who can change your team\'s profile info?',
            description: (
              'Select who is able to edit your team name, logo, description, and URL.'
            ),
            fieldKey: 'edit_organization',
            selectOptions: getOptions(ORG_SETTING_VALUE.MEMBER, ORG_SETTING_VALUE.ADMIN),
            trackingValue: 'team_info_dropdown'
          }
        ]
      },
      {
        heading: 'Libraries',
        fields: [
          {
            title: 'Who can create public libraries within your team?',
            description: (
              'Select who is able to make your team\'s libraries public.' +
              ' Public libraries are discoverable in search engine\'s results,' +
              ' like Google.'
            ),
            fieldKey: 'publish_libraries',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER),
            trackingValue: 'create_public_libraries_dropdown'
          },
          {
            title: 'Can admins edit libraries within the team?',
            description: (
              'Admins will be able to edit and delete non-private libraries within the team.'
            ),
            fieldKey: 'force_edit_libraries',
            selectOptions: getOptions(
              { label: 'No', value: ORG_SETTING_VALUE.DISABLED },
              { label: 'Yes', value: ORG_SETTING_VALUE.ADMIN }
            ),
            trackingValue: 'admins_edit_libraries_dropdown'
          },
          {
            title: 'Who can move libraries out of the team?',
            description: (
              'Select who is able to move libraries out of the team' +
              ' and into another location e.g. another team.'
            ),
            fieldKey: 'remove_libraries',
            selectOptions: getOptions(
              ORG_SETTING_VALUE.ADMIN,
              { label: 'Admins & library owners', value: ORG_SETTING_VALUE.MEMBER }
            ),
            trackingValue: 'move_libraries_dropdown'
          }
        ]
      },
      {
        heading: 'Members',
        fields: [
          {
            title: 'Who can see the members list of this team?',
            description: (
              'Select who is able to see the list of team members.' +
              ' "Anyone" means it is public and discoverable using' +
              ' search engines like Google.'
            ),
            fieldKey: 'view_members',
            selectOptions: getOptions(ORG_SETTING_VALUE.ANYONE, ORG_SETTING_VALUE.MEMBER),
            trackingValue: 'view_members_dropdown'
          },
          {
            title: 'Who can invite members to the team?',
            description: (
              'Select who is able to invite members to your team.'
            ),
            fieldKey: 'invite_members',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER),
            trackingValue: 'invite_members_dropdown'
          },
          {
            title: 'Who can message everyone in the team?',
            description: (
              'Select who can send a message to everyone in the team.'
            ),
            fieldKey: 'group_messaging',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER),
            trackingValue: 'message_everyone_dropdown'
          },
          {
            title: 'Who can see the settings page?',
            description: (
              'Select who is able to view the settings for this team.' +
              ' This does not allow them to edit the settings.'
            ),
            fieldKey: 'view_settings',
            selectOptions: getOptions(ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER),
            trackingValue: 'view_settings_dropdown'
          },
          {
            title: 'Can users join your team by verifying one of your team\'s email addresses?',
            description: (
              'When a user verifies an email from one of your email domains,' +
              ' add them to your team.'
            ),
            fieldKey: 'join_by_verifying',
            selectOptions: getOptions(
              { label: 'No', value: ORG_SETTING_VALUE.DISABLED },
              { label: 'Yes', value: ORG_SETTING_VALUE.NONMEMBERS }
            )
          }
        ],
        subComponent: 'kf-team-email-mapping'
      },
      {
        heading: 'Integrations',
        fields: [
          {
            title: 'Who can create and edit Slack integrations with Kifi?',
            description: (
              'Select who is able to create and edit Slack integrations with Kifi.' +
              ' Integrating with Slack will automatically send all keeps from' +
              ' a particular library to a Slack channel.'
            ),
            fieldKey: 'create_slack_integration',
            selectOptions: getOptions(ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER),
            trackingValue: 'slack_integration_dropdown'
          },
          {
            title: 'Who can export this team\'s keeps?',
            description: (
              'Select who is able to export keeps from public and team visible libraries.'
            ),
            fieldKey: 'export_keeps',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN),
            trackingValue: 'export_keeps_dropdown'
          }
        ]
      }
    ];

    $scope.updateSettings = function (feature, setting) {
      $window.addEventListener('beforeunload', onBeforeUnload);

      var settingsChange = {};
      settingsChange[feature] = setting;

      orgProfileService
      .setOrgSettings($scope.profile.id, settingsChange)
      .then(function(settingsData) {
        messageTicker({
          text: 'Settings have been saved',
          type: 'green'
        });
        profileService.fetchMe();
        $scope.settings = settingsData.settings;
        $state.reload();
      })
      ['catch'](function(response) {
        messageTicker({
          text: response.statusText + ': There was an error saving your settings',
          type: 'red'
        });
      })
      ['finally'](function () {
        $window.removeEventListener('beforeunload', onBeforeUnload);
      });
    };

    $scope.getHeadingAnchor = function (heading) {
      return heading.toLowerCase().replace(' ', '-');
    };

    $scope.onHoverUpsellPrivileges = function () {
      orgProfileService.trackEvent('user_viewed_page', $scope.profile, { action: 'viewPrivilegesUpsell' });
    };

    $scope.onClickUpsellPrivileges = function () {
      orgProfileService.trackEvent('user_clicked_page', $scope.profile, { action: 'clickPrivilegesUpsell' });
    };

    function getOptions() {
      var items = Array.prototype.slice.apply(arguments);
      var options = [ // This is what the <select>s will read from
        { label: 'No one', value: ORG_SETTING_VALUE.DISABLED },
        { label: 'Admins only', value: ORG_SETTING_VALUE.ADMIN },
        { label: 'Team members', value: ORG_SETTING_VALUE.MEMBER },
        { label: 'Non-members', value: ORG_SETTING_VALUE.NONMEMBERS },
        { label: 'Anyone', value: ORG_SETTING_VALUE.ANYONE }
      ];

      // If an option isn't in the list of items, discard it.
      return options.map(function (o) {
        var desired = items.filter(function (item) {
          return o.value === item || o.value === item.value;
        }).pop();

        if (desired) {
          return {
            label: desired.label || o.label,
            value: o.value
          };
        } else {
          return null;
        }
      }).filter(Boolean);
    }

    $scope.onClickTrack = function(trackingValue) {
      orgProfileService.trackEvent('user_clicked_page', $scope.profile, { type: 'org_profile', action: trackingValue });
    };

    function onBeforeUnload(e) {
      var message = 'We\'re still saving your settings. Are you sure you wish to leave this page?';
      (e || $window.event).returnValue = message;
      return message;
    }

    if ($scope.viewer.membership && $scope.viewer.membership.role === 'admin') {
      $scope.billingState = billingState;
    }

    $scope.kifiAdmin = ((profileService.me.experiments || []).indexOf('admin') !== -1);

    $timeout(function () {
      $scope.$emit('trackOrgProfileEvent', 'view', {
        type: 'org_profile:settings'
      });
    });
  }
]);
