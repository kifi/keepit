'use strict';

angular.module('kifi')

.directive('kfCreditBanner', [
  function () {
    return {
      restrict: 'A',
      templateUrl: 'billing/creditBanner.tpl.html',
      scope: {
        credit: '@'
      }
    };
  }
]);
