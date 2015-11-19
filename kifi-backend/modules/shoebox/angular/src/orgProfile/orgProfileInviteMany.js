'use strict';

angular.module('kifi')

.directive('kfOrgInviteMany', [
  '$rootScope', 'util', 'orgProfileService', 'profileService', '$timeout',
  'ORG_PERMISSION',
  function ($rootScope, util, orgProfileService, profileService, $timeout,
            ORG_PERMISSION) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'orgProfile/orgProfileInviteMany.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {
        scope.organization = scope.modalData.organization;

        var meOrg = profileService.me.orgs.filter(function (o) {
          return o.id === scope.organization.id;
        })[0];

        scope.hasManagePermission = meOrg && meOrg.viewer && meOrg.viewer.permissions && meOrg.viewer.permissions.indexOf(ORG_PERMISSION.MANAGE_PLAN) > -1;

        scope.state = {
          emails: ''
        };
        var trackingType = 'org_profile:invite:paste';

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

        scope.clickWhoToInvite = function() {
          scope.modalData.addMany = false;
          orgProfileService.trackEvent('user_clicked_page', scope.organization, { type: trackingType, action: 'WTI' });
        };

        [
          $rootScope.$on('$stateChangeStart', function () {
            scope.close();
          })
        ].forEach(function (deregister) {
          scope.$on('$destroy', deregister);
        });

        scope.$emit('trackOrgProfileEvent', 'view', { type: trackingType });
        $timeout(function () {
          element.find('textarea').focus();
        });
      }
    };
  }
]);
