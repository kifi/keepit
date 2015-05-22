'use strict';

angular.module('kifi')

.directive('kfLibraryMembers', [
  'libraryService', '$timeout', 'net',
  function (libraryService, $timeout, net) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'libraries/libraryMembers.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {

        var followerAccess = 'read_only';
        var collabAccess = 'read_write';
        var noAccess = 'none';
        var ownerOnlyInviteSetting = 'owner';
        var collabInviteSetting = 'collaborator';

        //
        // Smart Scroll
        //
        scope.moreMembers = true;
        scope.selectedMemberId = null;
        scope.memberList = [];
        scope.memberScrollDistance = '100%';

        scope.isMemberScrollDisabled = function () {
          return !(scope.moreMembers);
        };
        scope.memberScrollNext = function () {
          pageMembers();
        };

        var pageSize = 10;
        scope.offset = 0;
        var loading = false;

        function pageMembers() {
          if (loading) { return; }
          if (scope.library.id) {
            loading = true;
            libraryService.getMoreMembers(scope.library.id, pageSize, scope.offset).then(function (resp) {
              var members = resp.members;
              _.each(members, function(member) {
                member.isCollaborator = isCollaborator(member);
                member.isFollower = isFollower(member);
              });
              loading = false;
              if (members.length === 0) {
                scope.moreMembers = false;
              } else {
                scope.moreMembers = true;
                scope.offset += 1;
                _.remove(members, 'lastInvitedAt');
                scope.memberList.push.apply(scope.memberList, members);
              }
            });
          }
        }

        function isCollaborator(member) {
          return member.membership === collabAccess;
        }

        function isFollower(member) {
          return member.membership === followerAccess;
        }

        function updateMembership(member, access) {
          scope.selectedMemberId = member.id;
          return net.updateLibraryMembership(scope.library.id, member.id, {access: access});
        }

        function countFollowersAndCollaborators() {
          var memCounts = _.countBy(scope.memberList, 'membership');
          scope.library.numFollowers = memCounts[followerAccess] || 0;
          scope.library.numCollaborators = memCounts[collabAccess] || 0;
        }

        scope.toggleWhoCanInvite = function() {
          libraryService.modifyLibrary({
            'id': scope.library.id,
            'whoCanInvite': scope.collabCanInvite ? ownerOnlyInviteSetting : collabInviteSetting
          }, false);
          scope.collabCanInvite = !scope.collabCanInvite;
        };

        function changeMembership(access) {
          var m = _.find(scope.memberList, {id: scope.selectedMemberId});
          m.membership = access;
          m.isCollaborator = isCollaborator(m);
          m.isFollower = isFollower(m);
          countFollowersAndCollaborators();
        }

        scope.changeToFollower = function(member) {
          updateMembership(member, followerAccess).then(function() {
            changeMembership(followerAccess);
          });
        };

        scope.changeToCollaborator = function(member) {
          updateMembership(member, collabAccess).then(function() {
            changeMembership(collabAccess);
          });
        };

        scope.removeMember = function(member) {
          updateMembership(member, noAccess).then(function () {
            $timeout(function() {
              _.remove(scope.memberList, {id: scope.selectedMemberId});
              countFollowersAndCollaborators();
            });
          });
        };

        scope.close = function () {
          kfModalCtrl.close();
        };

        //
        // On link.
        //
        if (scope.modalData) {
          scope.library = scope.modalData.library;
          scope.collabCanInvite = scope.library.whoCanInvite === collabInviteSetting;
          scope.modalTitle = scope.library.name;
          scope.canManage = scope.modalData.canManageMembers;
          scope.currentPageOrigin = scope.modalData.currentPageOrigin;
          scope.amOwner = scope.modalData.amOwner;
        }
      }
    };
  }
]);
