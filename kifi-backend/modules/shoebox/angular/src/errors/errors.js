'use strict';

angular.module('kifi')

.directive('kfErrorNotFound', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'errors/notFound.tpl.html',
      link: function () {}
    };
  }
]);
