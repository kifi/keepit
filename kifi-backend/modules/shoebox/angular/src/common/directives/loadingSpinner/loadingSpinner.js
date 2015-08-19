'use strict';

angular.module('kifi')

.directive('kfLoadingSpinner', [
  function () {
    return {
      restrict: 'A',
      templateUrl: 'common/directives/loadingSpinner/loadingSpinner.tpl.html',
      scope: { hide: '=' },
      replace: true
    };
  }
]);
