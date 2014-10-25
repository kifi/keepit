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
            scope.visibleKeepLibraries = scope.keep.libraries;
          } else {
            scope.visibleKeepLibraries = [];
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
