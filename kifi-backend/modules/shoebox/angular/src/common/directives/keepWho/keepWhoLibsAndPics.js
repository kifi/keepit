'use strict';

angular.module('kifi')

.directive('kfKeepWhoLibsAndPics', [
  '$rootScope', 'friendService', 'keepWhoService', 'profileService',
  function ($rootScope, friendService, keepWhoService, profileService) {
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

        // For keep cards that search results on the search page, add and remove user library attribution
        // on the client side when the user keeps or unkeeps.
        var deregisterKeepAddedListener = $rootScope.$on('keepAdded', function (e, libSlug, keeps, library) {
          var visibleLibraryIds = _.pluck(scope.visibleKeepLibraries, 'id');

          _.each(keeps, function (keep) {
            if (!scope.keep.id &&                      // No scope.keep.id if the keep is not on a library page.
                scope.keep.url === keep.url &&
                library.kind === 'user_created' &&     // Do not show system libraries as attributions.
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

