'use strict';

angular.module('kifi')

.directive('kfOrgProfileSlackBox', [ '$rootScope', '$stateParams', 'orgProfileService',
  function ($rootScope, $stateParams, orgProfileService) {

  var trackingType = 'orgProfile';
  var errorCount = 0;

  return {
    restrict: 'A',
    scope: {
      organization: '=',
      showNewSlackButton: '='
    },
    templateUrl: 'orgProfile/orgProfileSlackBox.tpl.html',
    link: function(scope) {
      scope.userLoggedIn = $rootScope.userLoggedIn;
      scope.slackTeamId = scope.organization.slackTeam.id;
      scope.requestFailed = $stateParams.error;

      scope.trackClick = function(action) {
        var eventName = ($rootScope.userLoggedIn ? 'user' : 'visitor') + '_clicked_page';
        var attributes = {
          type: trackingType,
          action: action,
          origin: 'slackProfile',
          slackTeamId: scope.slackTeamId
        };
        orgProfileService.trackEvent(eventName, scope.organization, attributes);
      };

      scope.sendInvite = function() {
        if (scope.username) {
          var username = scope.username.replace(/@/g, '');
          orgProfileService.sendOrgMemberInviteViaSlack(scope.organization.id, username).then(function(data){
            if (data === '') {
              scope.inviteResult = 'Visit Slack and check your direct messages from Kifi bot.' +
               ' Click on the invitation link to join ' + scope.organization.name + '!';
            }
          })['catch'](function(resp) {
            if (resp.data.error === 'slack_user_not_found') {
              scope.$error = 'We can\'t find @' + username + ' in this team\'s Slack members.';
            } else if (resp.data.error === 'no_valid_token') {
              scope.$error = 'Your Slack admins have not allowed us to message their members.';
            } else {
              scope.$error = 'Something went wrong, please try again later.';
            }
            $rootScope.$emit('trackOrgProfileEvent', 'view', {
              type: trackingType,
              version: 'teamSpecificSlackDMInvite',
              errorCount: ++errorCount,
              errorType: (resp.data && resp.data.error) || 'unknown',
              username: username
            });
          });
        }
        scope.trackClick('clickedSendInvite');
      };
    }
  };
}]);
