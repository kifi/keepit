'use strict';

angular.module('kifi')

.directive('kfRelatedLibraries', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        heading: '@',
        libraryId: '@',
        relatedLibraries: '='
      },
      templateUrl: 'libraries/relatedLibraries.tpl.html',
      link: function (scope) {

        scope.clickSeeMore = function () {
          scope.$emit('trackLibraryEvent', 'click', { action: 'clickedLibraryRecSeeMore' });
        };
      }
    };
  }
]);
