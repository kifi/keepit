'use strict';

angular.module('kifi')

.directive('kfOrgInviteMany', [
  'util', 'orgProfileService',
  function (util, orgProfileService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'orgProfile/orgProfileInviteMany.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {
        scope.organization = scope.modalData.organization;
        scope.state = {
          emails: ''
        };

        scope.close = function () {
          kfModalCtrl.close();
        };

        function mapInviteEmail(email) {
          if (util.validateEmail(email)) {
            return {
              type: 'email',
              email: email,
              role: 'member'
            };
          } else {
            return null;
          }
        }

        scope.inviteMany = function () {
          var commaSeparatedEmails = scope.state.emails;
          var emails = commaSeparatedEmails.split(/\"|\'|;|,|<|>|\s/);
          var invites = emails.map(mapInviteEmail).filter(Boolean);

          return orgProfileService
          .sendOrgMemberInvite(scope.organization.id, {
            invites: invites
          })
          .then(function (responseData) {
            scope.sent = true;
            scope.inviteSentCount = responseData.result === 'success' && responseData.invitees && responseData.invitees.length;

            if (scope.inviteSentCount > 0) {
              scope.state.emails = '';
            }

            return responseData;
          })
          .then(scope.modalData.returnAction);
        };
      }
    };
  }
]);
