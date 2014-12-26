'use strict';

angular.module('kifi')

.directive('kfLibraryMiniCard', [
  '$rootScope', '$state', 'env', 'libraryService', 'profileService', 'friendService', 'modalService', 'userService', '$location',
  function ($rootScope, $state, env, libraryService, profileService, friendService, modalService, userService, $location) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        refLibrary: '&library',
        invite: '='
      },
      templateUrl: 'libraries/libraryMiniCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.library = _.cloneDeep(scope.refLibrary());
        scope.library.owner.profileUrl = env.origin + '/' + scope.library.owner.username;
        scope.showMiniCard = true;
        scope.inUserProfileBeta = userService.inUserProfileBeta();


        //
        // Internal helper methods.
        //
        function followLibrary() {
          libraryService.joinLibrary(scope.library.id).then(function (result) {
            if (result === 'already_joined') {
              modalService.openGenericErrorModal({
                modalData: {
                  genericErrorMessage: 'You are already following this library!'
                }
              });

              return;
            } else {
              if ($location.path() === scope.library.url) {
                $state.reload();
              } else {
                $location.path(scope.library.url);
              }
            }
          })['catch'](modalService.openGenericErrorModal);
        }

        function unfollowLibrary() {
          libraryService.leaveLibrary(scope.library.id).then(function () {
            $location.path(scope.library.url);
          })['catch'](modalService.openGenericErrorModal);
        }


        //
        // Scope methods.
        //
        scope.following = function () {
          return !scope.invite && !scope.library.isMine && scope.library.access === 'read_only';
        };

        scope.toggleFollow = function () {
          if (scope.isMine) {
            modalService.openGenericErrorModal({
              modalData: {
                genericErrorMessage: 'You cannot follow your own libraries!'
              }
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
          })['catch'](function () {
            scope.showMiniCard = false;
          });
        }
      }
    };
  }
]);
