'use strict';

angular.module('kifi')

.controller('OrgProfileMemberManageCtrl', [
  '$scope', 'profile', 'profileService', 'orgProfileService', 'modalService', 'Paginator', 'net',
  function($scope, profile, profileService, orgProfileService, modalService, Paginator, net) {
    function memberPageAnalytics(args) {
      orgProfileService.trackEvent('user_clicked_page', organization, args);
    }

    var organization = profile.organization;

    var memberLazyLoader = new Paginator(memberSource);

    function memberSource(pageNumber, pageSize) {
      return orgProfileService
        .getOrgMembers(organization.id, pageNumber * pageSize, pageSize)
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

    $scope.removeOrCancelMember = function (member, action) {
      if (action === 'cancel') {
        cancelInvite(member);
      } else if (action === 'remove') {
        removeMember(member);
      } else {
        throw new Error('Invalid action argument in removeOrCancelMember: ' + action);
      }
    };

    function removeMember(member) {
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
    }

    function cancelInvite(member) {
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
    }

    function removeMemberFromPage(member) {
      var index = $scope.members.indexOf(member);
      if (index > -1) {
        // Remove member from the list
        $scope.members.splice(index, 1);
      }
    }

    $scope.members = null;
    $scope.myMembership = $scope.membership;
    $scope.organization = organization;
    $scope.canInvite = $scope.myMembership.permissions && $scope.myMembership.permissions.indexOf('invite_members') > -1;
    $scope.me = profileService.me;

    function resetAndFetch() {
      orgProfileService.invalidateOrgProfileCache();
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

    $scope.$on('resetAndFetch', function() {
      resetAndFetch();
    });

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
          member: member,
          isMe: member.id === $scope.me.id,
          action: 'remove'
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
      modalService.open({
        template: 'orgProfile/orgProfileMemberRemoveModal.tpl.html',
        modalData: {
          organization: organization,
          member: member,
          isMe: member.id === $scope.me.id,
          action: 'cancel'
        },
        scope: $scope
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

    // Add a newly invited user to the list, but only if they aren't already present
    function liveAddMember(newMemberObj) {
      var shouldLiveAddMember = true;
      var m;

      for (var i = 0; i < $scope.members.length; i++) {
        m = $scope.members[i];
        if ((newMemberObj.id && newMemberObj.id === m.id) ||
            (newMemberObj.email && newMemberObj.email === m.email)) {
          shouldLiveAddMember = false;
          break;
        }
      }

      if (shouldLiveAddMember) {
        $scope.members.push(newMemberObj);
      }
    }

    $scope.openInviteModal = function (inviteType) {
      memberPageAnalytics({ action: 'clickedInviteBegin' });

      modalService.open({
        template: 'orgProfile/orgProfileInviteSearchModal.tpl.html',
        modalData: {
          organization: organization,
          inviteType: inviteType,
          currentPageOrigin: 'organizationPage',
          returnAction: function (inviteData) {
            var invitees = inviteData.invitees || [];

            invitees.forEach(function (invitee) {
              if (invitee.id) {
                net.user(invitee.id).then(function (response) {
                  var user = response.data;

                  if (user) {
                    user.lastInvitedAt = +new Date();
                    liveAddMember(user);
                  }
                });
              } else if (invitee.email){
                liveAddMember({
                  role: 'member',
                  lastInvitedAt: +new Date(),
                  email: invitee.email
                });
              }
            });

            memberPageAnalytics({ action: 'clickedInvite', orgInvitees: invitees });
          }
        }
      });
    };

    resetAndFetch();
  }
]);
