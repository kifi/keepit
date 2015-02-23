'use strict';

angular.module('kifi')

.directive('kfSuggestedSearches', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '='
      },
      templateUrl: 'libraries/suggestedSearches.tpl.html'
    };
  }
]);
