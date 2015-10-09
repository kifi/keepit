'use strict';

angular.module('kifi')

.controller('PaymentPlanCtrl', [
  '$rootScope', '$scope', '$state',  '$filter', 'billingService', 'modalService', 'billingState','StripeCheckout',
  function ($rootScope, $scope, $state, $filter, billingService, modalService, billingState, StripeCheckout) {
    $scope.billingState = billingState;

    var handler = StripeCheckout.configure({
      locale: 'auto'
    });

    $scope.openStripeCheckout = function () {
      // Open Checkout with further options
      handler
      .open({
        image: $filter('pic')($scope.profile),
        name: 'Kifi Paid Plan',
        description: 'Unlock awesome paid-only features',
        allowRememberMe: false,
        panelLabel: 'Save My Card'
      })
      .then(function (response) {
        var token = response[0];

        return billingService
        .setBillingCCToken($scope.profile.id, token.id)
        .then(function () {
          $state.reload();
        });
      })
      ['catch'](function () {
        modalService.openGenericErrorModal({
          modalData: {
            genericErrorMessage: 'Your payment information was not updated.'
          }
        });
      });
    };

    // Close Checkout on page navigation
    [
      $rootScope.$on('$stateChangeStart', function () {
        if (handler && handler.close) {
          handler.close();
        }
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });
  }
]);
