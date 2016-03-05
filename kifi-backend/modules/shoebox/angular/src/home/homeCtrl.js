'use strict';

angular.module('kifi')

.controller('HomeCtrl', [
  '$rootScope', '$scope', '$stateParams', 'profileService', 'messageTicker',
  function($rootScope, $scope, $stateParams, profileService, messageTicker) {

    var error = $stateParams.error;
    if (error) {
      var text;
      switch (error) {
        case 'slack_team_already_connected':
          text = 'That Slack team is already connected to another Kifi team.' +
            ' Please try a different Slack team or contact support@kifi.com to resolve any conflicts.';
          break;
        default:
          text = 'An error occurred. Please contact support@kifi.com if this error persists.';
          break;
      }
      messageTicker({ text: text, type: 'red', delay: 5000 });
    }

    $scope.showDelightedSurvey = profileService.prefs.show_delighted_question;

    $scope.$on('$destroy', $rootScope.$on('prefsChanged', function () {
      $scope.showDelightedSurvey = profileService.prefs.show_delighted_question;
    }));

    $scope.hideDelightedSurvey = function () {
      $scope.showDelightedSurvey = false;
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
