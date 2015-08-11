'use strict';

angular.module('kifi')

.controller('OrgProfileMemberManageCtrl', [
  '$scope', 'profile', 'profileService', 'orgProfileService', 'modalService', 'Paginator',
  function($scope, profile, profileService, orgProfileService, modalService, Paginator) {
    function memberPageAnalytics(args) {
      orgProfileService.trackEvent('user_clicked_page', organization, args);
    }

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
      var err = (response.data && response.data.error) || response.error;
      var message;

      if (!err) {
        return;
      }

      if (err === 'insufficient_permissions') {
        message = 'You don\'t have the privileges to modify this user.';
      }

      modalService.open({
        template: 'common/modal/genericErrorModal.tpl.html',
        modalData: {
          genericErrorMessage: message
        }
      });
    }

    $scope.removeMember = function (member) {
      orgProfileService.removeOrgMember(organization.id, {
          members: [{
            userId: member.id
          }]
        })
        .then(function success() {
          var action = (profileService.me.id === member.id ? 'clickedLeaveOrg' : 'clickedRemoveOrg');
          memberPageAnalytics({ action: action, orgMember: member.username });
          removeMemberFromPage(member);
        })
        ['catch'](handleErrorResponse);
    };

    function removeMemberFromPage(member) {
      var index = $scope.members.indexOf(member);
      if (index > -1) {
        // Remove member from the list
        $scope.members.splice(index, 1);
      }
    }

    $scope.members = [];
    $scope.myMembership = $scope.membership;
    $scope.organization = organization;
    $scope.canInvite = $scope.myMembership.permissions && $scope.myMembership.permissions.indexOf('invite_members') > -1;
    $scope.me = profileService.me;

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
    $scope.$on('toggledMember', function (e, member, isOpen) {
      var action = (isOpen ? 'clickedMemberToggleOpen' : 'clickedMemberToggleClosed');
      memberPageAnalytics({ action: action, orgMember: member.username });

      if (isOpen) {
        $scope.$broadcast('memberOpened', member);
      }
    });

    $scope.$on('removeMember', function (e, member) {
      modalService.open({
        template: 'orgProfile/orgProfileMemberRemoveModal.tpl.html',
        modalData: {
          organization: organization,
          member: member
        },
        scope: $scope
      });
    });

    $scope.$on('inviteMember', function (e, member, cb) {
      var promise = orgProfileService
        .sendOrgMemberInvite(organization.id, {
          invites: [{
            id: member.id ? member.id : undefined,
            email: member.email ? member.email : undefined,
            role: 'member'
          }]
        })
        .then(function () {
          memberPageAnalytics({ action: 'clickedInvite', orgMember: member.username });
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
        memberPageAnalytics({ action: 'clickedCancelInvite', orgMember: member.username });
        removeMemberFromPage(member);
      })
      ['catch'](handleErrorResponse);
    });

    $scope.$on('makeOwner', function(e, member) {
      modifyMemberRole(member, 'owner').then(function() {
        memberPageAnalytics({ action: 'clickedMakeOwner', orgMember: member.username });
      });
    });

    $scope.$on('promoteMember', function (e, member) {
      modifyMemberRole(member, 'admin').then(function () {
        memberPageAnalytics({ action: 'clickedMakeAdmin', orgMember: member.username });
      });
    });

    $scope.$on('demoteMember', function (e, member) {
      modifyMemberRole(member, 'member').then(function () {
        memberPageAnalytics({ action: 'clickedDemote', orgMember: member.username });
      });
    });

    $scope.$on('clickedAvatar', function (e, member) {
      memberPageAnalytics({ action: 'clickedMemberName', orgMember: member.username });
    });

    function modifyMemberRole(member, role) {
      return orgProfileService.modifyOrgMember(organization.id, {
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
      memberPageAnalytics({ action: 'clickedInviteBegin' });

      modalService.open({
        template: 'orgProfile/orgProfileInviteSearchModal.tpl.html',
        modalData: {
          organization: organization,
          inviteType: inviteType,
          currentPageOrigin: 'organizationPage',
          returnAction: function (response) {
            var invitees = response.data.invitees;
            memberPageAnalytics({ action: 'clickedInvite', orgInvitees: invitees });
          }
        }
      });
    };

    resetAndFetch();
  }
]);
