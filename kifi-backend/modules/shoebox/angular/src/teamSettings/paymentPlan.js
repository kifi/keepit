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

    var PREDEFINED_CYCLE_PERIOD = {
      1: 'Monthly',
      12: 'Annual'
    };

    var PREDEFINED_CYCLE_ADVERB = {
      1: 'Monthly',
      12: 'Annually'
    };

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
        name: 'Kifi Teams',
        description: 'Update your Teams Plan',
        allowRememberMe: false,
        panelLabel: 'Save My Card'
      })
      .then(function (response) {
        $scope.plan.newCard = response[0];
      });
    };

    $scope.warningModalOrSave = function () {
      if ($scope.selectedPlan && $scope.isFreePlanName($scope.selectedPlan.name)) {
        openDowngradeModal();
      } else if ($scope.isPaidPlanName($scope.plan.name) && !($scope.card && $scope.card.lastFour) && !$scope.plan.newCard) {
        modalService.openGenericErrorModal({
          modalData: {
            genericErrorMessage: 'Save unsuccessful. You must enter a card to upgrade.'
          }
        });
      } else {
        $scope.save();
      }
    };

    function openDowngradeModal () {
      modalService.open({
        template: 'teamSettings/downgradeConfirmModal.tpl.html',
        modalData: {
          save: function () {
            $scope.save();
          },
          close: function () {
            modalService.close();
          }
        }
      });
    }

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

      // Set pristine if the user moves back to the initial values
      if (!initializing && !$scope.plan.newCard && (newPlan.name === currentPlan.name && newPlan.cycle === currentPlan.cycle)) {
        resetForm();
      }
    }, true);

    function getCyclesByTier(tier) {
      var cyclesSoFar = [];
      var leastEfficientPlan;
      var extraText = '';
      var savingsPerUser;
      var totalSavings;

      if (!$scope.isFreePlanName(tier[0].name)) {
        leastEfficientPlan = getLeastEfficientPlan(tier);
      }

      return tier.map(function (plan) {
        if (cyclesSoFar.indexOf(plan.cycle) === -1) {
          cyclesSoFar.push(plan.cycle); // prevent duplicates

          if (leastEfficientPlan && plan !== leastEfficientPlan) {
            savingsPerUser = getSavings(leastEfficientPlan, plan);
            totalSavings = savingsPerUser * billingState.users;
            extraText = ' (You save ' + moneyFilter(totalSavings) + ' ' + PREDEFINED_CYCLE_ADVERB[plan.cycle] + ')';
          }

          return {
            value: plan.cycle,
            label: getCycleLabel(plan) + extraText
          };
        }
      }).filter(Boolean);
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

    function getPricePerUserPerCycle(plan) {
      return moneyUnwrapFilter(plan.pricePerUser) / moneyUnwrapFilter(plan.cycle);
    }

    function getSavings(lessEfficientPlan, moreEfficientPlan) {
      var ratio = moreEfficientPlan.cycle / lessEfficientPlan.cycle;
      var savings = moneyUnwrapFilter(lessEfficientPlan.pricePerUser) * ratio - moneyUnwrapFilter(moreEfficientPlan.pricePerUser);

      return savings;
    }

    function getCycleLabel(plan) {
      var period = PREDEFINED_CYCLE_PERIOD[plan.cycle] || 'Every ' + plan.cycle + ' months';
      var rate = moneyUnwrapFilter(plan.pricePerUser) / plan.cycle;
      var rateString = moneyFilter(rate);

      return period + ' - ' + rateString + ' per user per month';
    }

    $scope.save = function () {
      var saveSeriesDeferred = $q.defer();
      var saveSeriesPromise = saveSeriesDeferred.promise;

      // If nothing changed, pretend we saved it.
      if (!$scope.plan.newCard && $scope.planSelectsForm.$pristine) {
        messageTicker({
          text: 'Saved Successfully',
          type: 'green'
        });
        return;
      }

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
          $state.reload('orgProfile');
        }, 10);
      })
      ['catch'](modalService.genericErrorModal)
      ['finally'](function () {
        $window.removeEventListener('beforeunload', onBeforeUnload);
      });

      // Start the promise chain
      saveSeriesDeferred.resolve();
    };

    function onBeforeUnload(e) {
      var message = 'We\'re still saving your settings. Are you sure you wish to leave this page?';
      (e || $window.event).returnValue = message; // for Firefox
      return message;
    }

    function resetForm() {
      $scope.planSelectsForm.$setPristine();
      $scope.plan.newCard = null;
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
