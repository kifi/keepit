'use strict';

angular.module('kifi')

.controller('OrgProfileSlackCtrl', [
  '$window', '$rootScope', '$scope', '$stateParams', 'profileService', 'orgProfileService', 'profile', '$timeout',
  function ($window, $rootScope, $scope, $stateParams, profileService, orgProfileService, profile, $timeout) {
    $window.document.title = profile.organization.name + ' • Kifi <3 Slack';
    $scope.organization = profile.organization;
    var trackingType = 'orgLanding';

    $timeout(function () {
      $rootScope.$emit('trackOrgProfileEvent', 'view', {
        type: trackingType,
        version: 'teamSpecificSlack',
        slackTeamId: $stateParams.slackTeamId
      });
      if ($scope.requestFailed) {
        $rootScope.$emit('trackOrgProfileEvent', 'view', {
          type: trackingType,
          version: 'teamSpecificSlackDMInvite',
          slackTeamId: $stateParams.slackTeamId
        });
      }
    });
  }
]);
