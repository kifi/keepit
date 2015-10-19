'use strict';

angular.module('kifi')

.controller('PaymentPlanCtrl', [
  '$window', '$rootScope', '$scope', '$state', '$filter', '$q',
  'billingState', 'billingService', 'modalService', 'StripeCheckout',
  'messageTicker', 'paymentPlans', '$timeout',
  function ($window, $rootScope, $scope, $state, $filter, $q,
            billingState, billingService, modalService, StripeCheckout,
            messageTicker, paymentPlans, $timeout) {
    $scope.billingState = billingState;
    $scope.card = billingState.card;

    var picFilter = $filter('pic');
    var moneyFilter = $filter('money');
    var moneyUnwrapFilter = $filter('moneyUnwrap');

    var handler = StripeCheckout.configure({
      locale: 'auto'
    });

    $scope.openStripeCheckout = function () {
      // Open Checkout with further options
      handler
      .open({
        image: picFilter($scope.profile),
        name: 'Kifi Paid Plan',
        description: 'Unlock awesome paid-only features',
        allowRememberMe: false,
        panelLabel: 'Save My Card'
      })
      .then(function (response) {
        $scope.plan.newCard = response[0];
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
      $scope.planSelectsForm.$setDirty();
    };

    $scope.changePlanToStandard = function () {
      var standardTierPlans = plansByTier[Object.keys(plansByTier)[1]];
      var firstStandardTierPlan = standardTierPlans[0];
      $scope.plan.name = firstStandardTierPlan.name;
      $scope.planSelectsForm.$setDirty();
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
      cycle: currentPlan.cycle, //months
      newCard: null
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

    $scope.$watch('plan', function (newPlan, oldPlan) {
      var initializing = (
        newPlan.name === oldPlan.name &&
        newPlan.cycle === oldPlan.cycle
      );

      // Do nothing for the no-value options in the select
      if (!initializing && (newPlan.name === null || ($scope.isPaidPlanName(newPlan.name) && newPlan.cycle === null))) {
        return;
      }

      // Don't let users re-save their current plan
      if (!initializing && !$scope.plan.newCard && (newPlan.name === currentPlan.name && newPlan.cycle === currentPlan.cycle)) {
        resetForm();
        return;
      }

      if (initializing || newPlan.name !== oldPlan.name) {
        // Create the list of cycles available to this plan
        $scope.availableCycles = getCyclesByTier(plansByTier[newPlan.name]);

        // If the old plan selected 12 months cycle (for example),
        // and the new plan does not have a 12 months option,
        // then select the last cycle in the list.
        if (newPlan.name !== oldPlan.name) {
          var cycle;

          if (currentPlan.name === newPlan.name) {
            cycle = currentPlan.cycle;
          } else {
            var lastCycle = $scope.availableCycles.slice(-1)[0];
            cycle = lastCycle && lastCycle.value;
          }
          $scope.plan.cycle = cycle;
        }
      }

      // Set the currently selected plan object
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

        $scope.selectedPlan = selectedPlan;
      }
    }, true);

    function getPricePerUserPerCycle(plan) {
      return moneyUnwrapFilter(plan.pricePerUser) / moneyUnwrapFilter(plan.cycle);
    }

    function getLeastEfficientPlan(tier) {
      var highestPrice = getPricePerUserPerCycle(tier[0]);

      return tier.reduce(function (highestPricePlanSoFar, plan) {
        var price = getPricePerUserPerCycle(plan);
        if (price > highestPrice) {
          highestPrice = price;
          return plan;
        } else {
          return highestPricePlanSoFar;
        }
      });
    }

    function getSavings(lessEfficientPlan, moreEfficientPlan) {
      var ratio = moreEfficientPlan.cycle / lessEfficientPlan.cycle;
      var savings = moneyUnwrapFilter(lessEfficientPlan.pricePerUser) * ratio - moneyUnwrapFilter(moreEfficientPlan.pricePerUser);

      return savings;
    }

    function getCycleLabel(cycle) {
      var PREDEFINED_CYCLE_LABELS = {
        1: 'Monthly',
        12: 'Anually'
      };
      var label = PREDEFINED_CYCLE_LABELS[cycle];

      if (label) {
        return label;
      } else {
        return 'Every ' + cycle + ' months';
      }
    }

    function getCyclesByTier(tier) {
      var cyclesSoFar = [];
      var leastEfficientPlan;
      var extraText = '';
      var savings;

      if (!$scope.isFreePlanName(tier[0].name)) {
        leastEfficientPlan = getLeastEfficientPlan(tier);
      }

      return tier.map(function (plan) {
        if (cyclesSoFar.indexOf(plan.cycle) === -1) {
          cyclesSoFar.push(plan.cycle); // prevent duplicates

          if (leastEfficientPlan && plan !== leastEfficientPlan) {
            savings = getSavings(leastEfficientPlan, plan);
            extraText = ' (You save ' + moneyFilter(savings) + ')';
          }

          return {
            value: plan.cycle,
            label: getCycleLabel(plan.cycle) + extraText
          };
        }
      }).filter(Boolean);
    }

    $scope.save = function () {
      var saveSeriesDeferred = $q.defer();
      var saveSeriesPromise = saveSeriesDeferred.promise;

      saveSeriesPromise.then(function () {
        $window.addEventListener('beforeunload', onBeforeUnload);
      });

      if ($scope.plan.newCard) {
        saveSeriesPromise.then(function () {
          return billingService
          .setBillingCCToken($scope.profile.id, $scope.plan.newCard.id);
        });
      }

      saveSeriesPromise.then(function () {
        return billingService
        .setBillingPlan($scope.profile.id, ($scope.selectedPlan && $scope.selectedPlan.id) || currentPlan.id);
      })
      .then(function () {
        messageTicker({
          text: 'Saved Successfully',
          type: 'green'
        });
        resetForm();
        $timeout(function () {
          $state.reload('orgProfile.settings');
        }, 10);
      })
      ['catch'](modalService.genericErrorModal)
      ['finally'](function () {
        $window.removeEventListener('beforeunload', onBeforeUnload);
      });

      // Start the promise chain
      saveSeriesDeferred.resolve();
    };

    function resetForm() {
      $scope.planSelectsForm.$setPristine();
      $scope.plan.newCard = null;
    }

    function onBeforeUnload(e) {
      var message = 'We\'re still saving your settings. Are you sure you wish to leave this page?';
      (e || $window.event).returnValue = message; // for Firefox
      return message;
    }

    [
      // Close Checkout on page navigation
      $rootScope.$on('$stateChangeStart', function (event) {
        if (handler && handler.close) {
          handler.close();
        }

        if (!$scope.planSelectsForm.$pristine) {
          var confirmText = (
            'Are you sure you want to leave?' +
            ' You haven\'t saved your payment information.' +
            ' Click cancel to return and save.'
          );

          var confirmedLeave = !$window.confirm(confirmText);
          if (confirmedLeave) {
            event.preventDefault();
          }
        }
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });
  }
]);
