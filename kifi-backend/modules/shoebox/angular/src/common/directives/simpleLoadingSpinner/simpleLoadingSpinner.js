'use strict';

angular.module('kifi')

.directive('kfSimpleLoadingSpinner', [
  function () {
    return {
      restrict: 'A',
      templateUrl: 'common/directives/simpleLoadingSpinner/simpleLoadingSpinner.tpl.html',
      scope: { hide: '=' },
      replace: true
    };
  }
]);
