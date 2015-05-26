'use strict';

angular.module('kifi')

.directive('kfErrorNotFound', [
  function () {
    return {
      restrict: 'A',
      templateUrl: 'errors/notFound.tpl.html'
    };
  }
])

.directive('kfErrorBadToken', [
  function () {
    return {
      restrict: 'A',
      templateUrl: 'errors/badToken.tpl.html'
    };
  }
]);
