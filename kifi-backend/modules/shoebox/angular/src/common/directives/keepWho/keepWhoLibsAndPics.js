'use strict';

angular.module('kifi')

.directive('kfKeepWhoLibsAndPics', [
  '$rootScope', 'profileService', 'libraryService', 'env',
  function ($rootScope, profileService, libraryService, env) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoLibsAndPics.tpl.html',
      scope: {
        keep: '=',
        currentPageOrigin: '@'
      },
      link: function (scope) {
        scope.me = profileService.me;
        scope.isMyBookmark = scope.keep && scope.keep.isMyBookmark;
        scope.visibleKeepLibraries = scope.keep.libraries;
        scope.visibleKeepLibraries.forEach(function (lib) {
          lib.absPath = env.origin + lib.path;
        });

        scope.$emit('getCurrentLibrary', { callback: function (lib) {
          if (lib && lib.id) {
            var library = _.find(scope.visibleKeepLibraries, { id: lib.id });
            if (library) {
              library.hidden = true;
            }
          }
        }});

        // Add/remove library attribution on reco or search result when the user keeps/unkeeps.
        var deregisterKeepAddedListener = $rootScope.$on('keepAdded', function (e, keeps, library) {
          var visibleLibraryIds = _.pluck(scope.visibleKeepLibraries, 'id');
          _.each(keeps, function (keep) {
            if (!scope.keep.id &&                      // No scope.keep.id if the keep is not on a library page
                scope.keep.url === keep.url &&
                !libraryService.isLibraryMainOrSecret(library) &&     // Do not show system libraries
                !_.contains(visibleLibraryIds, library.id)) {
              scope.visibleKeepLibraries.push(library);
            }
          });
        });
        scope.$on('$destroy', deregisterKeepAddedListener);

        var deregisterKeepRemovedListener = $rootScope.$on('keepRemoved', function (e, removedKeep, library) {
          if (!scope.keep.id &&                        // No scope.keep.id if the keep is not on a library page.
              (scope.keep.url === removedKeep.url) &&
              (!libraryService.isLibraryMainOrSecret(library))) {     // Do not hide system libraries as attributions.
            _.remove(scope.visibleKeepLibraries, { id: library.id });
          }
        });
        scope.$on('$destroy', deregisterKeepRemovedListener);
      }
    };
  }
])

.directive('kfKeepWhoLib', ['$rootScope', 'platformService',
  function ($rootScope, platformService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoLib.tpl.html',
      scope: {
        library: '=',
        currentPageOrigin: '@'
      },
      link: function (scope) {
        scope.onLibraryAttributionClicked = function () {
          $rootScope.$emit('trackLibraryEvent', 'click', { action: 'clickedLibraryAttribution' });
        };
        scope.isBot = platformService.isBot();
      }
    };
  }
])

.directive('kfKeepWhoPic', function () {
  return {
    restrict: 'A',
    replace: true,
    templateUrl: 'common/directives/keepWho/keepWhoPic.tpl.html',
    scope: {
      keeper: '=',
      currentPageOrigin: '@'
    }
  };
});
