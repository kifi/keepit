'use strict';

angular.module('kifi')

.directive('kfLibraryMembers', [
  'libraryService',
  function (libraryService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'libraries/libraryMembers.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {

        //
        // Smart Scroll
        //
        scope.moreMembers = true;
        scope.memberList = [];
        scope.memberScrollDistance = '100%';
        scope.Math = Math;

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
                member.isCollaborator = member.membership === 'read_write';
              });
              loading = false;
              if (members.length === 0) {
                scope.moreMembers = false;
              } else {
                scope.moreMembers = true;
                scope.offset += 1;
                members = _.reject(members, function(m) { return m.lastInvitedAt; });
                scope.memberList.push.apply(scope.memberList, members);
              }
            });
          }
        }

        scope.toggleWhoCanInvite = function () {
          if (scope.collabCanInvite) {
            libraryService.modifyLibrary({'id': scope.library.id, 'whoCanInvite': 'owner'}, false);
          } else {
            libraryService.modifyLibrary({'id': scope.library.id, 'whoCanInvite':'collaborator'}, false);
          }
          scope.collabCanInvite = !scope.collabCanInvite;
        };

        scope.close = function () {
          kfModalCtrl.close();
        };

        //
        // On link.
        //
        if (scope.modalData) {
          scope.library = scope.modalData.library;
          scope.collabCanInvite = scope.library.whoCanInvite === 'collaborator';
          scope.modalTitle = scope.library.name;
          scope.canManage = scope.modalData.canManageMembers;
          scope.currentPageOrigin = scope.modalData.currentPageOrigin;
          scope.amOwner = scope.modalData.amOwner;
        }
      }
    };
  }
]);
