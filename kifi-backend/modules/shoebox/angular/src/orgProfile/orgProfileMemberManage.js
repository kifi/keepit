'use strict';

angular.module('kifi')

.controller('OrgProfileMemberManageCtrl', [
  '$scope', 'net', 'profile', 'profileService', 'modalService',
  function($scope, net, profile, profileService, modalService) {
    var organization = profile;

    $scope.members = [];
    $scope.me = null;

    net.getOrgMembers(organization.id).then(function (response) {
      $scope.members = response.data.members;
      $scope.me = $scope.members.filter(function (m) {
        return m.username === profileService.me.username;
      }).pop() || profileService.me;
    });

    // Let the other member lines know to close
    $scope.$on('openedMember', function (e, member) {
      $scope.$broadcast('memberOpened', member);
    });

    $scope.$on('removeMember', function (e, member) {
      console.log('remove ', member);
      net.removeOrgMember(organization.id, {
        members: [{
          userId: member.id
        }]
      });
    });

    $scope.$on('inviteMember', function () {
      //console.log('invite ', member);
    });

    $scope.$on('promoteMember', function (e, member) {
      //console.log('promote', member);
      net.modifyOrgMember(organization.id, {
        members: [{
          userId: member.id,
          newRole: 'member'
        }]
      });
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
