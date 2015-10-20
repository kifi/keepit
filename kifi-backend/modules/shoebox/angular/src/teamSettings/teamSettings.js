'use strict';

angular.module('kifi')

.controller('TeamSettingsCtrl', [
  '$window', '$rootScope', '$scope', '$state', '$sce', 'billingState',
  'orgProfileService', 'profileService', 'billingService', 'messageTicker',
  'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function ($window, $rootScope, $scope, $state, $sce, billingState,
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
            selectOptions: getOptions(ORG_SETTING_VALUE.MEMBER, ORG_SETTING_VALUE.ADMIN)
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
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER)
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
            )
          },
          {
            title: 'Who can move libraries out of the team?',
            description: (
              'Select who is able to move libraries out of the team' +
              ' and into another location e.g. another team.'
            ),
            fieldKey: 'remove_libraries',
            selectOptions: getOptions(
              ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN,
              { label: 'Admins & library owners', value: ORG_SETTING_VALUE.MEMBER }
            )
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
            selectOptions: getOptions(ORG_SETTING_VALUE.ANYONE, ORG_SETTING_VALUE.MEMBER)
          },
          {
            title: 'Who can invite members to the team?',
            description: (
              'Select who is able to invite members to your team.'
            ),
            fieldKey: 'invite_members',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER)
          },
          {
            title: 'Who can message everyone in the team?',
            description: (
              'Select who can send a message to everyone in the team.'
            ),
            fieldKey: 'group_messaging',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER)
          },
          {
            title: 'Who can see the settings page?',
            description: (
              'Select who is able to view the settings for this team. This does not allow them to edit the settings.'
            ),
            fieldKey: 'view_settings',
            selectOptions: getOptions(ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER)
          }
        ]
      },
      {
        heading: 'Integrations',
        fields: [
          {
            title: $sce.trustAsHtml(
              'Who can' +
              ' <a class="kf-link" href="http://blog.kifi.com/slack-library-subscriptions/" target="_blank">create a Slack integration</a>' +
              ' with Kifi?'
            ),
            description: (
              'Select who is able to create a Slack integration with Kifi.' +
              ' Integrating with Slack will automatically send all keeps from' +
              ' a particular library to a Slack channel.'
            ),
            fieldKey: 'create_slack_integration',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER)
          },
          {
            title: 'Who can export this team\'s keeps?',
            description: (
              'Select who is able to download all of your team\'s keeps for safe keeping.'
            ),
            fieldKey: 'export_keeps',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN)
          }
        ]
      }
    ];

    $scope.updateSettings = function () {
      $window.addEventListener('beforeunload', onBeforeUnload);

      orgProfileService
      .setOrgSettings($scope.profile.id, nestedSettingToFlatSetting($scope.settings))
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

    function getOptions() {
      var items = Array.prototype.slice.apply(arguments);
      var options = [ // This is what the <select>s will read from
        { label: 'No one', value: ORG_SETTING_VALUE.DISABLED },
        { label: 'Admins only', value: ORG_SETTING_VALUE.ADMIN },
        { label: 'Team members', value: ORG_SETTING_VALUE.MEMBER },
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

    // Transform
    //   nestedSettings = { 'example_setting_key': { setting: 'value', enabled: '' }, /* ... */ }
    // into
    //   flatSettings = { 'example_setting_key': 'value', /* ... */ }
    function nestedSettingToFlatSetting(nestedSettings) {
      var flatSettings = {};
      angular.forEach(nestedSettings, function (nestedSetting, key) {
        flatSettings[key] = nestedSetting.setting;
      });
      return flatSettings;
    }

    function onBeforeUnload(e) {
      var message = 'We\'re still saving your settings. Are you sure you wish to leave this page?';
      (e || $window.event).returnValue = message;
      return message;
    }

    if ($scope.viewer.membership && $scope.viewer.membership.role === 'admin') {
      $scope.billingState = billingState;
    }

    $scope.kifiAdmin = (profileService.me.experiments.indexOf('admin') !== -1);
  }
]);
