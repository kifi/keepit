'use strict';

angular.module('kifi')

.directive('kfLibraryMiniCard', [
  '$rootScope', '$state', 'libraryService', 'profileService', 'friendService', 'modalService', 'routeService', 'signupService', '$location',
  function ($rootScope, $state, libraryService, profileService, friendService, modalService, routeService, signupService, $location) {
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
        scope.showMiniCard = true;


        //
        // Internal helper methods.
        //
        function followLibrary() {
          if ($rootScope.userLoggedIn === false) {
            scope.showMiniCard = false;
            return signupService.register({libraryId: scope.library.id, intent: 'follow'});
          }

          libraryService.joinLibrary(scope.library.id).then(function () {
            // TODO: would be better user experience not to reload page or navigate away
            if ($location.path() === scope.library.url) {
              $state.reload();
            } else {
              $location.path(scope.library.url);
            }
          })['catch'](modalService.openGenericErrorModal);
        }

        function unfollowLibrary() {
          libraryService.leaveLibrary(scope.library.id).then(function () {
            // TODO: would be better user experience not to navigate away
            $location.path(scope.library.url);
          })['catch'](modalService.openGenericErrorModal);
        }


        //
        // Scope methods.
        //
        scope.following = function () {
          return !scope.invite && !scope.library.isMine && scope.isFollowing;
        };

        scope.toggleFollow = function () {
          if (scope.library.isMine) {
            modalService.openGenericErrorModal({
              modalData: {
                genericErrorMessage: 'You cannot follow your own libraries!'
              }
            });
          } else if (scope.isFollowing) {
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

        libraryService.getLibraryInfoById(scope.library.id).then(function (data) {
          _.assign(scope.library, data.library);
          scope.library.owner.image = friendService.getPictureUrlForUser(scope.library.owner);
          scope.library.owner.profileUrl = routeService.getProfileUrl(scope.library.owner.username);
          scope.isFollowing = data.membership === 'read_only';
        })['catch'](function () {
          scope.showMiniCard = false;
        });
      }
    };
  }
]);
