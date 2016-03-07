'use strict';

angular.module('kifi')

.controller('OrgProfileSlackCtrl', [
  '$window', '$rootScope', '$scope', '$stateParams', '$sce', 'orgProfileService', 'profile', '$timeout',
  function ($window, $rootScope, $scope, $stateParams, $sce, orgProfileService, profile, $timeout) {
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
        var username = $scope.username.replace(/@/g, '');
        orgProfileService.sendOrgMemberInviteViaSlack(profile.organization.id, username).then(function(data){
          var maybePermalinkTxt;
          if (data.permalink) {
            maybePermalinkTxt = $sce.trustAsHtml(', or <a class="kf-org-profile-slack-invite-permalink" href="' + data.permalink + '">click here</a>.');
          } else {
            maybePermalinkTxt = '.';
          }
          $scope.inviteResult = 'Visit Slack and check your direct messages from @Kifi' + maybePermalinkTxt +
           ' Click on the invitation link to join ' + profile.organization.name + '!';
        })['catch'](function(resp) {
          if (resp.data.error === 'slack_user_not_found') {
            $scope.$error = 'We can\'t find @' + username + ' in this team\'s Slack members.';
          } else if (resp.data.error === 'no_valid_token') {
            $scope.$error = 'Your Slack admins have not allowed us to message their members.';
          } else {
            $scope.$error = 'Something went wrong, please try again later.';
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
