'use strict';

angular.module('kifi')

.directive('kfLibraryMembers', [
  'libraryService', 'profileService', '$timeout', 'net',
  function (libraryService, profileService, $timeout, net) {
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
        scope.selectedMember = null;
        scope.memberList = [];
        scope.memberScrollDistance = '100%';
        scope.me = profileService.me;

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
              loading = false;
              if (members.length === 0) {
                scope.moreMembers = false;
              } else {
                scope.moreMembers = true;
                scope.offset += 1;
                members = filterMembers(members, scope.filterType);
                scope.memberList.push.apply(scope.memberList, members);
              }
            });
          }
        }

        //
        // Internal functions
        //
        function filterMembers(members, filterType) {
          if (filterType === 'followers_only') {
            members = _.filter(members, {membership : 'read_only'});
          } else if (filterType === 'collaborators_only') {
            members = _.filter(members, {membership : 'read_write'});
          }
          return members;
        }

        function updateMembership(member, access) {
          scope.selectedMember = member;
          return net.updateLibraryMembership(scope.library.id, member.id, {access: access});
        }

        function changeMembership(access) {
          var m = _.find(scope.memberList, {id: scope.selectedMember.id});
          var oldAccess = m.membership;
          m.membership = access;
          updateLibraryObject(oldAccess, access);
        }

        function updateLibraryObject(oldAccess, newAccess) {
          if (newAccess === oldAccess) {
            return;
          }

          // update own membership in library object (change access or remove membership)
          if (scope.selectedMember.id === scope.me.id) {
            if (newAccess) {
              scope.library.membership.access = newAccess;
            } else {
              scope.library.membership = null;
            }
          }

          // remove/decrement fields in library object based on oldAccess
          if (oldAccess === collabAccess) {
            scope.library.numCollaborators -= 1;
            _.remove(scope.library.collaborators, {id: scope.selectedMember.id});
          } else if (oldAccess === followerAccess) {
            scope.library.numFollowers -= 1;
            _.remove(scope.library.followers, {id: scope.selectedMember.id});
          }

          // add/increment fields in library object based on newAccess
          if (newAccess === collabAccess) {
            scope.library.numCollaborators += 1;
            scope.library.collaborators.push(scope.selectedMember);
          } else if (newAccess === followerAccess) {
            scope.library.numFollowers += 1;
            scope.library.followers.push(scope.selectedMember);
          }
        }

        //
        // Scope functions
        //
        scope.isCollaborator = function(member) {
          return member.membership === collabAccess;
        };

        scope.isFollower = function (member) {
          return member.membership === followerAccess;
        };

        scope.toggleWhoCanInvite = function() {
          libraryService.modifyLibrary({
            'id': scope.library.id,
            'whoCanInvite': scope.collabCanInvite ? ownerOnlyInviteSetting : collabInviteSetting
          }, false);
          scope.collabCanInvite = !scope.collabCanInvite;
          scope.library.whoCanInvite = scope.collabCanInvite ? collabInviteSetting : ownerOnlyInviteSetting;
        };

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
              _.remove(scope.memberList, {id: scope.selectedMember.id});
              updateLibraryObject(scope.selectedMember.membership);
            });
          });
        };

        scope.displayNumCollabs = function() {
          // if there are collaborators in this library, the UI includes owner as a collaborator
          // let numCollaborators follow this as well.
          return scope.library && scope.library.numCollaborators ? scope.library.numCollaborators + 1 : 0;
        };

        scope.close = function () {
          $timeout(function() {
            kfModalCtrl.close();
          });
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
          scope.filterType = scope.modalData.filterType;
        }
      }
    };
  }
]);
