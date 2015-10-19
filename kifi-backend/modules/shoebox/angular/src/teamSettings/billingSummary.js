'use strict';

angular.module('kifi')

.directive('kfBillingSummary', [
  '$filter',
  function ($filter) {
    var isZeroMoneyFilter = $filter('isZeroMoney');
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'teamSettings/billingSummary.tpl.html',
      scope: {
        billingState: '='
      },
      link: function ($scope) {
        $scope.isPaidPlan = function () {
          return !isZeroMoneyFilter($scope.billingState.plan.pricePerUser); // TODO(carlos): switch to server-side isPaid flag
        };
      }
    };
  }
]);
