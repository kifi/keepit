'use strict';

angular.module('kifi')

.controller('OrgProfileMemberManageCtrl', [
  '$scope', 'profile', 'profileService', 'orgProfileService', 'modalService',
  function($scope, profile, profileService, orgProfileService, modalService) {
    var organization = profile.organizationInfo;

    function handleErrorResponse(response) {
      var err = response.data.error;
      var message = null;

      if (err === 'insufficient_permissions') {
        message = 'You don\'t have the privileges to remove this user.';
      }

      modalService.open({
        template: 'common/modal/genericErrorModal.tpl.html',
        modalData: {
          genericErrorMessage: message
        }
      });
    }

    function removeMember(member) {
      var index = $scope.members.indexOf(member);
      if (index > -1) {
        // Remove member from the list
        $scope.members.splice(index, 1);
      }
    }

    $scope.members = [];
    $scope.me = null;

    orgProfileService
      .getOrgMembers(organization.id)
      .then(function success(memberData) {
        $scope.members = memberData.members;
        $scope.me = $scope.members.filter(function (m) {
          return m.username === profileService.me.username;
        }).pop() || profileService.me;
      })
      ['catch'](handleErrorResponse);

    // Let the other member lines know to close
    $scope.$on('openedMember', function (e, member) {
      $scope.$broadcast('memberOpened', member);
    });

    $scope.$on('removeMember', function (e, member) {
      orgProfileService.removeOrgMember(organization.id, {
          members: [{
            userId: member.id
          }]
        })
        .then(function success() {
          removeMember(member);
        })
        ['catch'](handleErrorResponse);
    });

    $scope.$on('inviteMember', function (e, member, cb) {
      //trackShareEvent('user_clicked_page', { action: 'clickedContact', subAction: 'kifiFriend' });
      var promise = orgProfileService
        .sendOrgMemberInvite(organization.id, {
          invites: [{
            id: member.id ? member.id : undefined,
            email: member.email ? member.email : undefined,
            role: 'member'
          }]
        })
        ['catch'](handleErrorResponse);

      cb(promise);
    });

    $scope.$on('cancelInvite', function (e, member) {
      orgProfileService.cancelOrgMemberInvite(organization.id, {
        cancel: [{
          id: member.id ? member.id : undefined,
          email: member.email ? member.email : undefined
        }]
      })
      .then(function success() {
        removeMember(member);
      })
      ['catch'](handleErrorResponse);
    });

    $scope.$on('promoteMember', function (e, member) {
      orgProfileService.modifyOrgMember(organization.id, {
        members: [{
          userId: member.id,
          newRole: 'admin'
        }]
      })
      .then(function success() {
        member.role = 'admin';
      })
      ['catch'](handleErrorResponse);
    });

    $scope.openInviteModal = function (inviteType) {
      modalService.open({
        template: 'orgProfile/orgProfileInviteSearchModal.tpl.html',
        modalData: {
          organization: organization,
          inviteType: inviteType,
          currentPageOrigin: 'organizationPage'
        }
      });
    };
  }
]);
