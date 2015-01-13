'use strict';

angular.module('kifi')

.directive('kfSmallLibraryCard', [
  '$location',
  function ($location) {
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
          $event.preventDefault();
          $location.path(scope.library.path).search('o', scope.origin);
          scope.$emit('trackLibraryEvent', 'click', { action: scope.action });
        };
      }
    };
  }
])

;
