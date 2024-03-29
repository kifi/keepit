'use strict';

angular.module('kifi')

.controller('HomeCtrl', [
  '$rootScope', '$scope', '$stateParams', '$q', 'profileService', 'messageTicker',
  function($rootScope, $scope, $stateParams, $q, profileService, messageTicker) {

    var error = $stateParams.error;
    if (error) {
      var text;
      switch (error) {
        case 'slack_team_already_connected':
          text = 'That Slack team is already connected to another Kifi team.' +
            ' Please try a different Slack team.';
          break;
        case 'membership_already_connected':
          text = 'It seems that your Slack account is already connected to a different Kifi account.' +
            ' Please log in using the Kifi account associated with your Slack account.';
          break;
        default:
          text = 'An error occurred.';
          break;
      }
      messageTicker({ text: text, type: 'red', delay: 5000 });
    }


    (Object.keys(profileService.prefs).length === 0 ? profileService.fetchPrefs() : $q.when(profileService.prefs)).then(function(prefs) {
      $scope.showAnnouncement = prefs.show_announcement;
    });

    $scope.hideAnnouncement = function() {
      $scope.showAnnouncement = false;
      profileService.updateLastSeenAnnouncement();
    };

    if ($stateParams.openImportModal === 'importBookmarks') {
      $rootScope.$emit('showGlobalModal', 'importBookmarks');
    } else if ($stateParams.openImportModal === 'importBookmarkFile') {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
    } else if ($stateParams.openImportModal) {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
    }
  }
]);
