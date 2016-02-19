'use strict';

angular.module('kifi')

.controller('OrgProfileSlackCtrl', [
  '$window', '$rootScope', '$scope', '$stateParams', 'orgProfileService', 'profile', '$timeout',
  function ($window, $rootScope, $scope, $stateParams, orgProfileService, profile, $timeout) {
    $window.document.title = profile.organization.name + ' • Kifi <3 Slack';
    $scope.userLoggedIn = $rootScope.userLoggedIn;
    $scope.slackTeamId = $stateParams.slackTeamId;

    $scope.linkSlack = function (e) {
      var url = 'https://www.kifi.com/link/slack?slackTeamId=' + $scope.slackTeamId;
      try {
        e.target.href = url;
      } catch (err) {
        $window.location = url;
      }
      $scope.trackClick('clickedAuthSlack');
    };

    $scope.trackClick = function(action) {
      var eventName = ($rootScope.userLoggedIn ? 'user' : 'visitor') + '_clicked_page';
      var attributes = {
        type: 'orgLanding',
        action: action,
        origin: 'slackProfile',
        slackTeamId: $stateParams.slackTeamId
      };
      orgProfileService.trackEvent(eventName, profile.organization, attributes);
    };


    $timeout(function () {
      $rootScope.$emit('trackOrgProfileEvent', 'view', {
        type: 'orgLanding',
        version: 'teamSpecificSlack'
      });
    });
  }
]);
