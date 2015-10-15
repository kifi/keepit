'use strict';

angular.module('kifi')

.controller('PaymentPlanCtrl', [
  '$window', '$rootScope', '$scope', '$state', '$filter', 'billingState',
  'billingService', 'modalService', 'StripeCheckout', 'messageTicker',
  'paymentPlans',
  function ($window, $rootScope, $scope, $state, $filter, billingState,
            billingService, modalService, StripeCheckout, messageTicker,
            paymentPlans) {
    $scope.card = billingState.card;

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

    $scope.changePlanToFree = function () {
      var freeTierPlans = plansByTier[Object.keys(plansByTier)[0]];
      var firstFreeTierPlan = freeTierPlans[0];
      $scope.plan.name = firstFreeTierPlan.name;
      $scope.plan.cycle = firstFreeTierPlan.cycle;
    };

    $scope.changePlanToStandard = function () {
      var standardTierPlans = plansByTier[Object.keys(plansByTier)[1]];
      var firstStandardTierPlan = standardTierPlans[0];
      $scope.plan.name = firstStandardTierPlan.name;
    };

    var plansByTier = paymentPlans.plans;

    var flatPlans = Object.keys(plansByTier).reduce(function (acc, key) {
      return acc.concat(plansByTier[key]);
    }, []);

    var currentPlan = flatPlans.filter(function (p) {
      return p.id === paymentPlans.current;
    }).pop();

    $scope.availableTiers = Object.keys(plansByTier).map(function (tier) {
      return {
        value: tier,
        label: plansByTier[tier][0].name
      };
    });

    $scope.plan = {
      name: currentPlan.name,
      cycle: currentPlan.cycle //month
    };

    $scope.isNoPlanName = function (planName) {
      return !planName;
    };

    $scope.isPaidPlanName = function (planName) {
      return planName && planName.toLowerCase().indexOf('free') === -1;
    };

    $scope.isFreePlanName = function (planName) {
      return planName && planName.toLowerCase().indexOf('free') > -1;
    };

    $scope.$watch('plan', function (newValue, oldValue) {
      var initializing = (
        newValue.name === oldValue.name &&
        newValue.cycle === oldValue.cycle
      );

      if (!initializing && (newValue.name === null || newValue.cycle === null)) {
        return;
      }

      if (initializing || newValue.name !== oldValue.name) {
        // Create the list of cycles available to this plan
        var cyclesSoFar = [];
        $scope.availableCycles = plansByTier[newValue.name].map(function (plan) {
          if (cyclesSoFar.indexOf(plan.cycle) === -1) {
            cyclesSoFar.push(plan.cycle); // prevent duplicates

            return {
              value: plan.cycle,
              label: plan.cycle + ' month' + (plan.cycle > 1 ? 's' : '')
            };
          }
        }).filter(Boolean);

        // If the old plan selected 12 months cycle (for example),
        // and the new plan does not have a 12 months option,
        // then select the first cycle in the list.
        if (newValue.name !== oldValue.name) {
          $scope.plan.cycle = $scope.availableCycles[0] && $scope.availableCycles[0].value;
        }
      }

      if (!initializing) {
        var selectedPlan = flatPlans.filter(function (p) {
          return (
            p.name === $scope.plan.name &&
            p.cycle === $scope.plan.cycle
          );
        }).pop();

        if (!selectedPlan) {
          return;
        }

        billingService
        .setBillingPlan($scope.profile.id, selectedPlan.id)
        .then(function () {
          messageTicker({
            text: 'Saved Successfully',
            type: 'green'
          });
        })
        ['catch'](modalService.openGenericErrorModal);
      }
    }, true);

    [
      // Close Checkout on page navigation
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
