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
      }).pop();
    });

    // Let the other member lines know to close
    $scope.$on('openedMember', function (e, member) {
      $scope.$broadcast('memberOpened', member);
    });

    $scope.$on('removeMember', function () {
      //console.log('remove ', member);
    });

    $scope.$on('inviteMember', function () {
      //console.log('invite ', member);
    });

    $scope.$on('promoteMember', function () {
      //console.log('promote', member);
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
