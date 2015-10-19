'use strict';

angular.module('kifi')

.directive('kfBillingSummary',
  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'teamSettings/billingSummary.tpl.html',
      scope: {
        billingState: '='
      },
      link: function ($scope) {
        $scope.isPaidPlan = function () {
          return $scope.billingState.plan.pricePerUser !== '$0.00'; // TODO: i18n will break this
        };
      }
    };
  }
);
