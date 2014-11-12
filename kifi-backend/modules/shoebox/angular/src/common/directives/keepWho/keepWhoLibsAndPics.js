'use strict';

angular.module('kifi')

.directive('kfKeepWhoLibsAndPics', [
  '$rootScope', 'friendService', 'keepWhoService', 'profileService', 'libraryService',
  function ($rootScope, friendService, keepWhoService, profileService, libraryService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoLibsAndPics.tpl.html',
      scope: {
        keep: '='
      },
      link: function (scope) {
        //
        // Internal functions.
        //
        function isSystemLibrary(library) {
          if (library.kind) {
            return library.kind === 'system_main' || library.kind === 'system_secret';
          } else {
            return libraryService.isSystemLibrary(library.id);
          }
        }

        function sortVisibleLibraries() {
          scope.visibleKeepLibraries = _.sortBy(scope.visibleKeepLibraries, function (library) {
            // List system libraries first.
            if (library.system) { return 0; }

            // List user-owned libraries next.
            if (library.isMine) { return 1; }

            // List friend libraries last.
            return 2;
          });
        }

        function init() {
          scope.me = profileService.me;
          scope.getPicUrl = keepWhoService.getPicUrl;
          scope.getName = keepWhoService.getName;
          scope.isMyBookmark = scope.keep && scope.keep.isMyBookmark;

          scope.visibleKeepLibraries = _.map(scope.keep.libraries, function (library) {
            library.system = isSystemLibrary(library);
            return library;
          });
          sortVisibleLibraries();

          // Do not show attribution for the library the page is displaying.
          scope.$emit('getCurrentLibrary', {
            callback: function (lib) {
              if (lib && lib.id) {
                var library = _.find(scope.visibleKeepLibraries, { id: lib.id });
                if (library) {
                  library.hidden = true;
                }
              }
            }
          });
        }


        //
        // Watches and listeners.
        //

        // Add user library attribution when the user keeps.
        var deregisterKeepAddedListener = $rootScope.$on('keepAdded', function (e, libSlug, keeps, library) {
          _.each(keeps, function (keep) {
            if (scope.keep.url === keep.url) {
              library.keeperPic = friendService.getPictureUrlForUser(profileService.me);
              library.isMine = library.owner.id === profileService.me.id;
              library.system = isSystemLibrary(library);

              // A library is kept in only one system library at a time, so remove any
              // previous system libraries.
              if (library.system) {
                scope.visibleKeepLibraries = _.reject(scope.visibleKeepLibraries, 'system');
              }

              scope.visibleKeepLibraries.push(library);
            }
          });

          sortVisibleLibraries();
        });
        scope.$on('$destroy', deregisterKeepAddedListener);

        // Remove userlibrary attribution when the user unkeeps.
        var deregisterKeepRemovedListener = $rootScope.$on('keepRemoved', function (e, removedKeep, library) {
          if (scope.keep.url === removedKeep.url) {
            _.remove(scope.visibleKeepLibraries, { id: library.id });
          }
        });
        scope.$on('$destroy', deregisterKeepRemovedListener);


        init();
      }
    };
  }
])

.directive('kfKeepWhoLib', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoLib.tpl.html',
      scope: {
        library: '='
      }
    };
  }
])

.directive('kfKeepWhoPic', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoPic.tpl.html',
      scope: {
        keeper: '='
      }
    };
  }
]);

