'use strict';

angular.module('kifi')

.controller('OrgProfileSlackCtrl', [
  '$window', '$rootScope', '$scope', '$stateParams', 'orgProfileService', 'profile', '$timeout',
  function ($window, $rootScope, $scope, $stateParams, orgProfileService, profile, $timeout) {
    $window.document.title = profile.organization.name + ' • Kifi <3 Slack';
    $scope.userLoggedIn = $rootScope.userLoggedIn;
    $scope.slackTeamId = $stateParams.slackTeamId;
    $scope.requestFailed = $stateParams.error === 'access_denied';
    $scope.username = '';

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

    $scope.sendInvite = function() {
      if ($scope.username) {
        var username = $scope.username[0] === '@' ? $scope.username.substring(1) : $scope.username;
        orgProfileService.sendOrgMemberInviteViaSlack(profile.organization.id, username).then(function(data){
          if (data === '') {
            $scope.inviteResult = 'Your invite was successfully sent to @' + username + ', click on the link to accept it!';
          }
        })['catch'](function(resp) {
          if (resp.data.error === 'slack_user_not_found') {
            $scope.$error = 'We can\'t find @' + username + ' in this team\'s Slack members';
          } else {
            $scope.$error = 'Something went wrong. Please try again later.';
          }
        });
      }
      $scope.trackClick('clickedSendInvite');
    };


    $timeout(function () {
      $rootScope.$emit('trackOrgProfileEvent', 'view', {
        type: 'orgLanding',
        version: 'teamSpecificSlack'
      });
    });
  }
]);
