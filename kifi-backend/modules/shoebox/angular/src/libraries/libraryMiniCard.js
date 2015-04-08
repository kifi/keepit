'use strict';

angular.module('kifi')

.directive('kfLibraryMiniCard', [
  '$rootScope', '$state', 'libraryService', 'modalService', 'signupService', '$location',
  function ($rootScope, $state, libraryService, modalService, signupService, $location) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'libraries/libraryMiniCard.tpl.html',
      link: function (scope) {

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
              $location.url(scope.library.url);
            }
          })['catch'](modalService.openGenericErrorModal);
        }

        function unfollowLibrary() {
          libraryService.leaveLibrary(scope.library.id).then(function () {
            // TODO: would be better user experience not to navigate away
            $location.url(scope.library.url);
          })['catch'](modalService.openGenericErrorModal);
        }


        //
        // Scope methods.
        //
        scope.isMyLibrary = function () {
          return libraryService.isMyLibrary(scope.library);
        };

        scope.following = function () {
          return !scope.isMyLibrary() && scope.isFollowing;
        };

        scope.toggleFollow = function () {
          if (scope.isFollowing) {
            unfollowLibrary();
          } else  {
            followLibrary();
          }
        };

        scope.view = function () {
          $location.url(scope.library.url);
        };


        //
        // On link.
        //

        libraryService.getLibraryInfoById(scope.library.id).then(function (data) {
          scope.library = data.library;
          scope.isFollowing = data.membership === 'read_only';
          scope.showMiniCard = true;
        })['catch'](function () {
          scope.showMiniCard = false;
        });
      }
    };
  }
]);
