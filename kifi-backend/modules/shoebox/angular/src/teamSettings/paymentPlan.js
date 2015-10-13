'use strict';

angular.module('kifi')

.controller('PaymentPlanCtrl', [
  '$window', '$rootScope', '$scope', '$state', '$filter', 'billingState', 'billingService',
  'modalService', 'StripeCheckout', 'messageTicker',
  function ($window, $rootScope, $scope, $state, $filter, billingState, billingService,
            modalService, StripeCheckout, messageTicker) {
    $scope.card = billingState.card;

    $scope.plan = {
      tier: 'free',
      cycle: 1 //month
    };

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

    $scope.savePlanChanges = function () {
      messageTicker({
        text: 'Saved Successfully',
        type: 'green'
      });
    };

    $scope.changePlanToFree = function () {
      $scope.plan.tier = 'free';
    };

    $scope.changePlanToStandard = function () {
      $scope.plan.tier = 'standard';
    };

    $scope.$watch('plan', function (newValue, oldValue) {
      if (newValue.tier === oldValue.tier &&
          newValue.cycle === oldValue.cycle) {
        return;
      }

      if (newValue.tier === 'enterprise') {
        $window.open('mailto:billing@kifi.com');
        newValue.tier = oldValue.tier;
        return;
      }

      $scope.savePlanChanges();
    }, true);

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
