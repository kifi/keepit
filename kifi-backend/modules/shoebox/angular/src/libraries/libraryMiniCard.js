'use strict';

angular.module('kifi')

.directive('kfLibraryMiniCard', [
  '$rootScope', '$route', 'libraryService', 'profileService', 'friendService', 'modalService', '$location',
  function ($rootScope, $route, libraryService, profileService, friendService, modalService, $location) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        invite: '='
      },
      templateUrl: 'libraries/libraryMiniCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        //
        // Internal helper methods.
        //
        function followLibrary() {
          libraryService.joinLibrary(scope.library.id).then(function (result) {
            if (result === 'already_joined') {
              scope.genericErrorMessage = 'You are already following this library!';
              modalService.open({
                template: 'common/modal/genericErrorModal.tpl.html',
                scope: scope
              });
              return;
            } else {
              if ($location.path() === scope.library.url) {
                $route.reload();
              } else {
                $location.path(scope.library.url);
              }
            }
          });
        }

        function unfollowLibrary() {
          libraryService.leaveLibrary(scope.library.id).then(function () {
            $location.path(scope.library.url);
          });
        }


        //
        // Scope methods.
        //
        scope.following = function () {
          return !scope.invite && !scope.library.isMine && scope.library.access === 'read_only';
        };

        scope.toggleFollow = function () {
          if (scope.isMine) {
            scope.genericErrorMessage = 'You cannot follow your own libraries!';
            modalService.open({
              template: 'common/modal/genericErrorModal.tpl.html',
              scope: scope
            });
            return;
          }

          if (scope.following()) {
            unfollowLibrary();
          } else  {
            followLibrary();
          }
        };

        scope.view = function () {
          $location.path(scope.library.url);
        };


        //
        // On link.
        //

        // Fetch full library summary if we don't have it already.
        if (!scope.library.isMine) {
          libraryService.getLibrarySummaryById(scope.library.id).then(function (data) {
            _.assign(scope.library, data.library);

            scope.library.owner.image = friendService.getPictureUrlForUser(scope.library.owner);
            scope.library.access = data.membership;
            scope.library.isMine = scope.library.access === 'owner';
          });
        }
      }
    };
  }
]);
