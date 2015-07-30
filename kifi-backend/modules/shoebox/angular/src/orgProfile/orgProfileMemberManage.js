'use strict';

angular.module('kifi')

.controller('OrgProfileMemberManageCtrl', [
  '$scope', 'profile', 'profileService', 'orgProfileService', 'modalService', 'Paginator',
  function($scope, profile, profileService, orgProfileService, modalService, Paginator) {
    var organization = profile.organization;

    var memberLazyLoader = new Paginator(memberSource);

    function memberSource(pageNumber, pageSize) {
      return orgProfileService
        .getOrgMembers(organization.id, pageNumber * pageSize, pageSize) // TODO: Waiting on a fix. I shouldn't have to multiply.
        .then(function (memberData) {
          return memberData.members;
        });
    }

    function handleErrorResponse(response) {
      var err = 'error' in response.data ? response.data.error : null;
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
    $scope.myMembership = $scope.membership;
    $scope.organization = organization;

    function resetAndFetch() {
      memberLazyLoader.reset();
      $scope.fetchMembers();
    }

    $scope.hasMoreMembers = function () {
      return memberLazyLoader.hasMore();
    };

    $scope.fetchMembers = function () {
      memberLazyLoader
        .fetch()
        .then(function (members) {
          $scope.members = members;
        })
        ['catch'](handleErrorResponse);
    };

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
      modifyMemberRole(member, 'admin');
    });

    $scope.$on('demoteMember', function (e, member) {
      modifyMemberRole(member, 'member');
    });

    function modifyMemberRole(member, role) {
      orgProfileService.modifyOrgMember(organization.id, {
        members: [{
          userId: member.id,
          newRole: role
        }]
      })
      .then(function success() {
        member.role = role;
      })
      ['catch'](handleErrorResponse);
    }

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

    resetAndFetch();
  }
]);
