'use strict';

angular.module('kifi')

.controller('OrgProfileSlackCtrl', [
  '$window', '$location', '$rootScope', '$analytics', '$scope', '$stateParams',
  'profile', 'orgProfileService', 'originTrackingService', '$timeout',
  function ($window, $location, $rootScope, $analytics, $scope, $stateParams,
            profile, orgProfileService, originTrackingService, $timeout) {
    $window.document.title = profile.organization.name + ' • Kifi <3 Slack';
    $scope.userLoggedIn = $rootScope.userLoggedIn;
    $scope.slackTeamId = $stateParams.slackTeamId;

    $scope.linkSlack = function (e) {
      e.target.href = 'https://www.kifi.com/link/slack?slackTeamId=' + $scope.slackTeamId;
    };

    $timeout(function () {
      $rootScope.$emit('trackOrgProfileEvent', 'view', {
        type: 'orgLanding',
        version: 'teamSpecificSlack'
      });
    });
  }
]);
