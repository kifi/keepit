'use strict';

angular.module('kifi')

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
])

.directive('kfKeepWhoPics', [
  'keepWhoService', 'profileService', 'libraryService',
  function (keepWhoService, profileService, libraryService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoPics.tpl.html',
      scope: {
        keep: '='
      },
      link: function (scope) {
        var keep = scope.keep;

        scope.me = profileService.me;
        scope.getPicUrl = keepWhoService.getPicUrl;
        scope.getName = keepWhoService.getName;
        scope.isMyBookmark = scope.keep && scope.keep.isMyBookmark;
        scope.librariesEnabled = libraryService.isAllowed();
        
        if (scope.librariesEnabled) {
          scope.visibleKeepLibaries = _.union(keep.libraries, keep.myLibraries);
          scope.$emit('getCurrentLibrary', { callback: function (currentLibrary) {
            var currentLibraryIdx = _.findIndex(scope.visibleKeepLibaries, { id: currentLibrary.id });
            if (currentLibraryIdx > -1) {
              scope.visibleKeepLibaries.splice(currentLibraryIdx, 1);
            }
          }});
        }
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
]);
