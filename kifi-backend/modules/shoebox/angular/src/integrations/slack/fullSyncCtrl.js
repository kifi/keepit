'use strict';

angular.module('kifi')

.controller('SlackIntegrationFullSyncCtrl', [
  '$analytics', '$window', '$scope', '$state', 'slackService', 'profileService',
  function ($analytics, $window, $scope, $state, slackService, profileService) {
    $scope.me = profileService.me;


    $scope.onClickedSyncAllSlackChannels = function() {
      $window.location = '/site/slack/add';
      $analytics.eventTrack('user_clicked_page', { type: 'slackFullSync', action: 'slackSyncAllChannels' });
    };
  }
]);
