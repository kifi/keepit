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
        scope.me = profileService.me;
        scope.getPicUrl = keepWhoService.getPicUrl;
        scope.getName = keepWhoService.getName;
        scope.isMyBookmark = scope.keep && scope.keep.isMyBookmark;
        scope.visibleKeepLibraries = scope.keep.libraries;

        scope.$emit('getCurrentLibrary', { callback: function (lib) {
          if (lib && lib.id) {
            var library = _.find(scope.visibleKeepLibraries, { id: lib.id });
            if (library) {
              library.hidden = true;
            }
          }
        }});

        // For keep cards that search results on the search page, add and remove user library attribution
        // on the client side when the user keeps or unkeeps.
        var deregisterKeepAddedListener = $rootScope.$on('keepAdded', function (e, libSlug, keeps, library) {
          var visibleLibraryIds = _.pluck(scope.visibleKeepLibraries, 'id');

          _.each(keeps, function (keep) {
            if (!scope.keep.id &&                      // No scope.keep.id if the keep is not on a library page.
                scope.keep.url === keep.url &&
                !libraryService.isSystemLibrary(library) &&     // Do not show system libraries as attributions.
                !_.contains(visibleLibraryIds, library.id)) {
              library.keeperPic = friendService.getPictureUrlForUser(profileService.me);
              scope.visibleKeepLibraries.push(library);
            }
          });
        });
        scope.$on('$destroy', deregisterKeepAddedListener);

        var deregisterKeepRemovedListener = $rootScope.$on('keepRemoved', function (e, removedKeep, library) {
          if (!scope.keep.id &&                        // No scope.keep.id if the keep is not on a library page.
              (scope.keep.url === removedKeep.url) &&
              (library.kind === 'user_created')) {     // Do not hide system libraries as attributions.
            _.remove(scope.visibleKeepLibraries, { id: library.id });
          }
        });
        scope.$on('$destroy', deregisterKeepRemovedListener);
      }
    };
  }
])

.directive('kfKeepWhoLib', ['$rootScope',
  function ($rootScope) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoLib.tpl.html',
      scope: {
        library: '='
      },
      link: function (scope) {
        scope.onLibraryAttributionClicked = function () {
          $rootScope.$emit('trackLibraryEvent', 'click', {
            type: 'libraryLanding',
            action: 'clickedLibraryAttribution'
          });
        };
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

