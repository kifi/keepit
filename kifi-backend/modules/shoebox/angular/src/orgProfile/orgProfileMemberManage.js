'use strict';

angular.module('kifi')

.controller('OrgProfileMemberManageCtrl', [
  '$scope', 'net', 'profile', 'profileService', 'modalService',
  function($scope, net, profile, profileService, modalService) {
    var organization = profile;

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

    net.getOrgMembers(organization.id).then(function (response) {
      $scope.members = response.data.members;
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
      net.removeOrgMember(organization.id, {
        members: [{
          userId: member.id
        }]
      })
      .then(function () {
        removeMember(member);
      })
      ['catch'](handleErrorResponse);
    });

    $scope.$on('inviteMember', function (e, member, cb) {
      //trackShareEvent('user_clicked_page', { action: 'clickedContact', subAction: 'kifiFriend' });
      var promise = net.sendOrgMemberInvite(organization.id, {
        invites: [{
          id: member.id ? member.id : undefined,
          email: member.email ? member.email : undefined,
          role: 'member'
        }]
      })
      ['catch'](handleErrorResponse);

      cb(promise)
    });

    $scope.$on('cancelInvite', function (e, member) {
      net.cancelOrgMemberInvite(organization.id, {
        cancel: [{
          id: member.id ? member.id : undefined,
          email: member.email ? member.email : undefined,
        }]
      })
      .then(function () {
        removeMember(member);
      })
      ['catch'](handleErrorResponse);
    });

    $scope.$on('promoteMember', function (e, member) {
      var promise = net.modifyOrgMember(organization.id, {
        members: [{
          userId: member.id,
          newRole: 'owner'
        }]
      })
      .then(function () {
        member.role = 'owner';
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
