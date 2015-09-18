'use strict';

angular.module('kifi')

.controller('OrgProfileSettingsCtrl', [
  'orgProfileService', '$scope', 'messageTicker', '$window',
  function (orgProfileService, $scope, messageTicker, $window) {
    var initialized = false;

    $scope.options = { // This is what the <select>s will read from
      member: 'Members only',
      admin: 'Admins only',
      anyone: 'Anyone',
      noone: 'No one'
    };

    var firstOption = Object.keys($scope.options)[0];
    $scope.settings = {
      publish_libraries: firstOption,
      invite_members: firstOption,
      group_messaging: firstOption,
      view_members: firstOption,
      create_slack_integration: firstOption
    }; // This is what the <select>s will mutate

    orgProfileService.getOrgSettings($scope.profile.id).then(function(settings) {
      // TODO (Adam): Settings coming down look different going up. We'll need to parse this:
      $scope.settings = settings;
      initialized = true;
    })['catch'](function(response) {
      messageTicker({
        text: response.statusText + ': Could not retrieve your settings. Please refresh and try again',
        type: 'red',
        delay: 0
      });
    });

    $scope.$watch('settings', function (newVal, oldVal) {
      if (newVal !== oldVal && initialized) {
        // Warn the user if they try to leave the page before the setting
        // has been successfully saved
        $window.onbeforeunload = function() {
          return 'We\'re still saving your settings. Are you sure you wish to leave this page?';
        };

        var settings = $scope.settings.settings;
        var newSettings = {};
        Object.keys($scope.settings.settings).forEach(function (key) {
          newSettings[key] = settings[key].setting;
        });

        orgProfileService.setOrgSettings($scope.profile.id, newSettings).then(function(settings) {
          messageTicker({
            text: 'Settings have been saved',
            type: 'green'
          });
          $window.onbeforeunload = function() { return null; };
          $scope.settings = settings;
        })['catch'](function(response) {
          messageTicker({
            text: response.statusText + ': There was an error saving your settings',
            type: 'red'
          });
        });
      }
    }, true);
  }
]);
