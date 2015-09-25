'use strict';

angular.module('kifi')

.directive('kfSmallLibraryCard', [
  '$location', 'modalService', '$window', 'platformService', 'LIB_PERMISSION',
  function ($location, modalService, $window, platformService, LIB_PERMISSION) {
    var currentPageOrigin = 'libraryPage.RelatedLibraries';

    function canModifyCollaborators(lib) {
      return (
        lib.membership && (
          lib.membership.permissions.indexOf(LIB_PERMISSION.INVITE_COLLABORATORS) !== -1 ||
          lib.membership.permissions.indexOf(LIB_PERMISSION.REMOVE_MEMBERS) !== -1
        )
      );
    }

    function updateCollaborators(numCollaborators, ignored, scope) {
      var n = 3; // at most 4 circles, one spot reserved for owner
      if (canModifyCollaborators(scope.library)) {
        n--; // one spot reserved for add collaborator button
      }
      scope.maxNumCollaboratorsToShow = numCollaborators > n ? n - 1 : n;  // one spot may be reserved for +N button
    }

    function openCollaboratorsList(lib) {
      if (!platformService.isSupportedMobilePlatform()) {
        modalService.open({
          template: 'libraries/libraryMembersModal.tpl.html',
          modalData: {
            library: lib,
            canManageMembers: canModifyCollaborators(lib),
            amOwner: (lib.membership || {}).access === 'owner',
            filterType: 'collaborators_only',
            currentPageOrigin: currentPageOrigin
          }
        });
      }
    }

    function openFollowersList(lib) {
      if (!platformService.isSupportedMobilePlatform()) {
        modalService.open({
          template: 'libraries/libraryFollowersModal.tpl.html',
          modalData: {
            library: lib,
            currentPageOrigin: currentPageOrigin
          }
        });
      }
    }

    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        action: '@',
        origin: '@'
      },
      templateUrl: 'libraries/smallLibraryCard.tpl.html',
      link: function (scope) {
        scope.maxNumFollowersToShow = 3;

        if (canModifyCollaborators(scope.library)) {
          scope.$watch('lib.numCollaborators', updateCollaborators);
        } else {
          updateCollaborators(scope.library.numCollaborators, null, scope);
        }

        scope.openCollaboratorsList = openCollaboratorsList;
        scope.openFollowersList = openFollowersList;

        scope.clickCard = function () {
          scope.$emit('trackLibraryEvent', 'click', {action: scope.action});
        };
      }
    };
  }
])

;
