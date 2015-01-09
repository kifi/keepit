'use strict';

angular.module('kifi')

.directive('kfSmallLibraryCard', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        origin: '@',
        action: '@'
      },
      templateUrl: 'libraries/smallLibraryCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.clickCard = function ($event) {
          $event.target.href = scope.library.path + '?o=' + scope.origin;
          scope.$emit('trackLibraryEvent', 'click', { action: scope.action });
        };
      }
    };
  }
])

;
