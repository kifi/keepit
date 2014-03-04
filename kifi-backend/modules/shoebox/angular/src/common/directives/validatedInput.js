'use strict';

angular.module('kifi.validatedInput', [])

.directive('kfValidatedInput', [
  function() {
    return {
      restrict: 'A',
      scope: {
        isInvalid: '=',
        header: '=',
        body: '='
      },
      transclude: true,
      templateUrl: 'common/directives/validatedInput.tpl.html'
    }
  }
]);
