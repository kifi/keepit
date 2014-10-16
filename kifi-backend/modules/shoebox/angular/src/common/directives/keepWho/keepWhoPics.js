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
  'keepWhoService', 'profileService',
  function (keepWhoService, profileService) {
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
