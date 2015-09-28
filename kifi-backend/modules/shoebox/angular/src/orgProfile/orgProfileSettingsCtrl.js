'use strict';

angular.module('kifi')

.controller('OrgProfileSettingsCtrl', [
  '$window', '$scope', 'orgProfileService', 'profileService', 'billingService',
  'settings', 'messageTicker', 'ORG_SETTING_VALUE',
  function ($window, $scope, orgProfileService, profileService, billingService,
            settings, messageTicker, ORG_SETTING_VALUE) {
    $scope.settings = settings.settings;

    $scope.settingsSectionTemplateData = [
      {
        heading: '',
        fields: [
          {
            title: 'Who can change team settings?',
            description: (
              'Select who is able to edit your team name, logo, description, and URL'
            ),
            fieldKey: 'edit_organization',
            selectOptions: getOptions(ORG_SETTING_VALUE.MEMBER, ORG_SETTING_VALUE.ADMIN)
          }
        ]
      },
      {
        heading: 'Library settings',
        fields: [
          {
            title: 'Who can create public libraries within your team?',
            description: (
              'Select who is able to make your teams\' libraries public.' +
              ' Public libraries are discoverable in search engine\'s results,' +
              ' like Google.'
            ),
            fieldKey: 'publish_libraries',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER)
          },
          {
            title: 'Who can edit libraries within your team?',
            description: (
              'Select who is able to edit any library settings such as' +
              ' visibility, title, and membership.'
            ),
            fieldKey: 'force_edit_libraries',
            selectOptions: getOptions('disabled', 'admin', 'member')
          },
          {
            title: 'Who can move libraries out of the team page?',
            description: (
              'Select who is able to move libraries out of the company' +
              ' and into another location ex. another team.'
            ),
            fieldKey: 'remove_libraries',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER)
          }
        ]
      },
      {
        heading: 'Member settings',
        fields: [
          {
            title: 'Who can see the members page of this team?',
            description: (
              'Select who is able to see the team\'s members page.' +
              ' "Anyone" means it\'s also discoverable in other' +
              ' search engine\'s results like Google.'
            ),
            fieldKey: 'view_members',
            selectOptions: getOptions(ORG_SETTING_VALUE.ANYONE, ORG_SETTING_VALUE.MEMBER)
          },
          {
            title: 'Who can invite members to join the team?',
            description: (
              'Members of the team have access to your team visible libraries.' +
              ' In most scenarios they can also keep to any public or team visible library.'
            ),
            fieldKey: 'invite_members',
            selectOptions: getOptions(ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER)
          },
          {
            title: 'Who can message everyone in the team?',
            description: (
              'Send a same page chat to everyone in the team with just a couple of clicks.'
            ),
            fieldKey: 'group_messaging',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER)
          }
        ]
      },
      {
        heading: 'Integrations',
        fields: [
          {
            title: 'Who can create a slack integration with Kifi?',
            description: (
              'Send all of your keeps from a particular library, automatically, to a slack channel.'
            ),
            fieldKey: 'create_slack_integration',
            selectOptions: getOptions(ORG_SETTING_VALUE.DISABLED, ORG_SETTING_VALUE.ADMIN, ORG_SETTING_VALUE.MEMBER)
          }
        ]
      }
    ];

    $scope.updateSettings = function () {
      $window.onbeforeunload = onBeforeUnload;

      orgProfileService
      .setOrgSettings($scope.profile.id, nestedSettingToFlatSetting($scope.settings))
      .then(function(settingsData) {
        messageTicker({
          text: 'Settings have been saved',
          type: 'green'
        });
        profileService.fetchMe();
        $window.onbeforeunload = null;
        $scope.settings = settingsData.settings;
      })['catch'](function(response) {
        messageTicker({
          text: response.statusText + ': There was an error saving your settings',
          type: 'red'
        });
      });
    };

    $scope.getHeadingAnchor = function (heading) {
      return heading.toLowerCase().replace(' ', '-');
    };

    function getOptions() {
      var items = Array.prototype.slice.apply(arguments);
      var options = [ // This is what the <select>s will read from
        { label: 'Admins only', value: ORG_SETTING_VALUE.ADMIN },
        { label: 'Members only', value: ORG_SETTING_VALUE.MEMBER },
        { label: 'Anyone', value: ORG_SETTING_VALUE.ANYONE },
        { label: 'No one', value: ORG_SETTING_VALUE.DISABLED }
      ];

      return items.map(function (item) {
        // Grab the option matching the value from items
        var match = options.filter(function (o) {
          return o.value === item;
        }).pop();

        return match;
      }).filter(Boolean); // filter out non-matches
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

    function onBeforeUnload() {
      return 'We\'re still saving your settings. Are you sure you wish to leave this page?';
    }

    billingService
    .getBillingState($scope.profile.id)
    .then(function (stateData) {
      $scope.billingState = stateData;
    });
  }
]);
