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
        scope.me = profileService.me;
        scope.getPicUrl = keepWhoService.getPicUrl;
        scope.getName = keepWhoService.getName;
        scope.isMyBookmark = scope.keep && scope.keep.isMyBookmark;
        scope.librariesEnabled = libraryService.isAllowed();

        function updateVisibleKeepLibraries() {
          if (scope.librariesEnabled) {
            scope.visibleKeepLibraries = _.union(scope.keep.libraries, scope.keep.myLibraries);
            scope.$emit('getCurrentLibrary', { callback: function (currentLibrary) {
              var currentLibraryIdx = _.findIndex(scope.visibleKeepLibraries, { id: currentLibrary.id });
              if (currentLibraryIdx > -1) {
                scope.visibleKeepLibraries.splice(currentLibraryIdx, 1);
              }
            }});
          }
        }

        scope.$watch('keep.myLibraries.length', updateVisibleKeepLibraries);
        updateVisibleKeepLibraries();
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
