'use strict';

angular.module('kifi')

.directive('kfLibraryVisibilitySelector', [
  '$timeout',
  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'libraries/libraryVisibilitySelector.tpl.html',
      scope: {
        library: '=',
        space: '='
      },
      link: function (scope) {
        scope.spaceIsOrg = function () {
          return !!scope.space && 'numMembers' in scope.space;
        };
      }
    };
  }
]);
