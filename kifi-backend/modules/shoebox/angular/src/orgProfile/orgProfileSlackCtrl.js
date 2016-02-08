'use strict';

angular.module('kifi')

.controller('OrgProfileSlackCtrl', [
  '$window', '$rootScope', '$scope', '$stateParams', 'profile', '$timeout',
  function ($window, $rootScope, $scope, $stateParams, profile, $timeout) {
    $window.document.title = profile.organization.name + ' • Kifi <3 Slack';
    $scope.userLoggedIn = $rootScope.userLoggedIn;
    $scope.slackTeamId = $stateParams.slackTeamId;

    $scope.linkSlack = function (e) {
      var url = 'https://www.kifi.com/link/slack?slackTeamId=' + $scope.slackTeamId;
      try {
        e.target.href = url;
      } catch (e) {
        $window.location = url;
      }
    };

    $timeout(function () {
      $rootScope.$emit('trackOrgProfileEvent', 'view', {
        type: 'orgLanding',
        version: 'teamSpecificSlack'
      });
    });
  }
]);
