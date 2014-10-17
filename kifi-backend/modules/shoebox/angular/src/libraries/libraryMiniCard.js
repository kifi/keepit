'use strict';

angular.module('kifi')

.directive('kfLibraryMiniCard', ['libraryService', 'profileService', 'friendService', '$window', '$location',
  function (libraryService, profileService, friendService, $window, $location) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        libraryId: '='
      },
      templateUrl: 'libraries/libraryMiniCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        if (scope.libraryId == null) {
          return;
        }
        libraryService.getLibrarySummaryById(scope.libraryId).then(function (lib) {
          scope.curatorName = function () {
            return lib.library.owner.firstName + ' ' + lib.library.owner.lastName;
          };
          scope.curatorImage = function () {
            return friendService.getPictureUrlForUser(lib.library.owner);
          };
          scope.numKeeps = function () {
            return lib.library.numKeeps;
          };
          scope.numFollowers = function () {
            return lib.library.numFollowers;
          };
          scope.libraryName = function () {
            return lib.library.name;
          };
          scope.visibility = function () {
            return lib.library.visibility;
          };
          scope.isMine = function () {
            return lib.membership === 'owner';
          };
          scope.following = function () {
            return lib.membership === 'read_only';
          };

          function followLibrary() {
            libraryService.joinLibrary(scope.libraryId).then(function (result) {
              if (result === 'already_joined') {
                $window.alert('You are already following this library!');
                return;
              } else {
                lib.membership = 'read_only';
                lib.numFollowers = lib.numFollowers + 1;
              }
            });
          }

          function unfollowLibrary() {
            libraryService.leaveLibrary(scope.libraryId).then( function () {
              lib.membership = 'none';
              lib.numFollowers = lib.numFollowers - 1;
            });
          }

          scope.toggleFollow = function () {
            if (scope.isMine()) {
              $window.alert('You cannot follow your own Libraries!');
              return;
            }

            if (scope.following()) {
              unfollowLibrary();
            } else  {
              followLibrary();
            }
          };

          scope.view = function () {
            $location.path(lib.library.url);
          };


        });

      }

    };
  }
]);
