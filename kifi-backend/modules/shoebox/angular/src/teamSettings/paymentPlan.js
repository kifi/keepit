'use strict';

angular.module('kifi')

.controller('PaymentPlanCtrl', [
  '$window', '$rootScope', '$scope', '$state', '$filter', '$q', '$timeout',
  'billingState', 'billingService', 'modalService',
  'profileService', 'StripeCheckout', 'messageTicker', 'paymentPlans',
  'ORG_PERMISSION',
  function ($window, $rootScope, $scope, $state, $filter, $q, $timeout,
            billingState, billingService, modalService,
            profileService, StripeCheckout, messageTicker, paymentPlans,
            ORG_PERMISSION) {
    $scope.billingState = billingState;
    $scope.card = billingState.card;
    $scope.disableSaveButton = false;
    $scope.trackingType = 'org_profile:settings:payment_plan';
    $scope.canRedeemCredit = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.REDEEM_CREDIT_CODE) !== -1);

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
      $scope.addCardPending = true;
      $scope.trackClick('credit_card:add_card');
      var me = profileService.me;
      var emailObject = (me.primaryEmail || me.emails[0] || {}); // extra defensive
      var emailAddress = emailObject.address;


      // Open Checkout with further options
      handler
      .open({
        image: picFilter($scope.profile),
        email: emailAddress,
        name: 'Kifi Teams',
        description: 'Update your team\'s plan',
        allowRememberMe: false,
        panelLabel: 'Save My Card'
      })
      .then(function (response) {
        var token = response[0];

        return billingService
        .createNewCard($scope.profile.id, token.id);
      })
      .then(function (kifiCardData) {
        $scope.plan.newCard = kifiCardData;
        $scope.cardError = false;
      })
      ['finally'](function () {
        $scope.addCardPending = false;
      });
    };

    $scope.warningModalOrSave = function () {
      $scope.trackClick('save');
      if (!$scope.isFreePlanName($scope.currentPlan.name) && $scope.isFreePlanName($scope.plan.name)) {
        openDowngradeModal();
      } else if ($scope.isPaidPlanName($scope.plan.name) && !($scope.card && $scope.card.lastFour) && !$scope.plan.newCard) {
        $scope.cardError = true;
      } else {
        $scope.cardError = false;
        $scope.save();
      }
    };

    function openDowngradeModal () {
      modalService.open({
        template: 'teamSettings/planDowngradeConfirmModal.tpl.html',
        modalData: {
          save: function () {
            $scope.trackClick('downgrade_modal:downgrade');
            $scope.save();
          },
          close: function () {
            $scope.trackClick('downgrade_modal:cancel');
            modalService.close();
          }
        }
      });
    }

    $scope.changePlanToFree = function (retried) {
      retried = (typeof retried === 'boolean' ? retried : false);

      // Sometimes the controller can run before ng-form assigns the form object
      // to the scope. If that happens, try one more time after a timeout
      if ($scope.planSelectsForm) {
        var freeTierPlans = plansByTier[Object.keys(plansByTier)[0]];
        var firstFreeTierPlan = freeTierPlans[0];
        $scope.plan.name = firstFreeTierPlan.name;
        $scope.plan.cycle = firstFreeTierPlan.cycle;
        $scope.planSelectsForm.$setDirty();
        $scope.trackClick('free_card:downgrade');
      } else {
        if (!retried) {
          $timeout(function () {
            $scope.changePlanToFree(true);
          });
        } else {
          $scope.trackClick('free_card:downgrade:fail');
        }
      }
    };

    $scope.changePlanToStandard = function (retried) {
      retried = (typeof retried === 'boolean' ? retried : false);

      // Sometimes the controller can run before ng-form assigns the form object
      // to the scope. If that happens, try one more time after a timeout.
      if ($scope.planSelectsForm) {
        var standardTierPlans = plansByTier[Object.keys(plansByTier)[1]];
        var firstStandardTierPlan = standardTierPlans[0];
        $scope.plan.name = firstStandardTierPlan.name;
        $scope.planSelectsForm.$setDirty();
        $scope.trackClick('standard_card:upgrade');
      } else {
        if (!retried) {
          $timeout(function () {
            $scope.changePlanToStandard(true);
          });
        } else {
          $scope.trackClick('standard_card:upgrade:fail');
        }
      }
    };

    var plansByTier = paymentPlans.plans;

    var flatPlans = Object.keys(plansByTier).reduce(function (acc, key) {
      return acc.concat(plansByTier[key]);
    }, []);

    $scope.currentPlan = flatPlans.filter(function (p) {
      return p.id === paymentPlans.current;
    }).pop();

    $scope.availableTiers = Object.keys(plansByTier).map(function (tier) {
      return {
        value: tier,
        label: plansByTier[tier][0].name
      };
    });

    $scope.plan = {
      name: $scope.currentPlan.name,
      cycle: $scope.currentPlan.cycle, //months
      newCard: null
    };

    $scope.billingPreview = null;
    $scope.addCardPending = false;

    $scope.isNoPlanName = function (planName) {
      return !planName;
    };

    $scope.isPaidPlanName = function (planName) {
      return planName && planName.toLowerCase().indexOf('free') === -1;
    };

    $scope.isFreePlanName = function (planName) {
      return planName && planName.toLowerCase().indexOf('free') > -1;
    };

    $scope.getSelectedPlan = function (planView) {
      return flatPlans.filter(function (p) {
        return (
          p.name === planView.name &&
          p.cycle === planView.cycle
        );
      }).pop();
    };

    $scope.$watch('plan', function (newPlan, oldPlan) {
      var initializing = (
        newPlan.name === oldPlan.name &&
        newPlan.cycle === oldPlan.cycle &&
        newPlan.newCard === oldPlan.newCard
      );

      if (initializing || newPlan.name !== oldPlan.name) {
        // Create the list of cycles available to this plan
        $scope.availableCycles = getCyclesByTier(plansByTier[newPlan.name]);

        // If the old plan selected 12 months cycle (for example),
        // and the new plan does not have a 12 months option,
        // then select the last cycle in the list.
        if (newPlan.name !== oldPlan.name) {
          var cycle;

          if ($scope.currentPlan.name === newPlan.name) {
            cycle = $scope.currentPlan.cycle;
          } else {
            var lastCycle = $scope.availableCycles.slice(-1)[0];
            cycle = lastCycle && lastCycle.value;
          }
          $scope.plan.cycle = cycle;
        }
      }

      // Set pristine if the user moves back to the initial values
      if (!initializing && !$scope.plan.newCard && (newPlan.name === $scope.currentPlan.name && newPlan.cycle === $scope.currentPlan.cycle)) {
        resetForm();
        $scope.billingPreview = null; // outside resetForm because we want preview on ?upgrade=true
      } else {
        var selectedPlan = $scope.getSelectedPlan($scope.plan);
        var card = $scope.plan.newCard || $scope.billingState.card;
        if (!initializing && card) {
          billingService
          .getBillingStatePreview($scope.profile.id, selectedPlan.id, card.id)
          .then(function (billingPreview) {
            $scope.billingPreview = billingPreview;
          })
          ['catch'](function () {
            $scope.billingPreview = null;
          });
        }
      }

      $scope.disableSaveButton = false;
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
      var savePromise;
      var selectedPlan = $scope.getSelectedPlan($scope.plan);
      var hasNewPlan = ($scope.currentPlan.id !== selectedPlan.id);

      // If nothing changed, pretend we saved it.
      if (!$scope.plan.newCard && !hasNewPlan && $scope.planSelectsForm.$pristine) {
        messageTicker({
          text: 'Saved Successfully',
          type: 'green'
        });
        return;
      }

      $scope.disableSaveButton = true;
      $window.addEventListener('beforeunload', onBeforeUnload);

      if ($scope.plan.newCard && hasNewPlan) { // both plan and card
        savePromise = billingService
        .updateAccountState($scope.profile.id, selectedPlan.id, $scope.plan.newCard.id)
        .then(function () {
          messageTicker({
            text: 'You successfully updated your plan and card.',
            type: 'green'
          });
        });
      } else if ($scope.plan.newCard) { // just card
        savePromise = billingService
        .setDefaultCard($scope.profile.id, $scope.plan.newCard.id)
        .then(function () {
          $scope.card = $scope.plan.newCard; // prevent flash while we load the new plan
          messageTicker({
            text: 'You successfully changed your card.',
            type: 'green'
          });
        });
      } else if (hasNewPlan) { // just plan
        savePromise = billingService
        .setBillingPlan($scope.profile.id, selectedPlan.id)
        .then(function () {
          var upgraded = $scope.isPaidPlanName(selectedPlan.name);
          messageTicker({
            text: upgraded ? 'You successfully upgraded your team\'s plan.' : 'You downgraded your team\'s plan.',
            type: 'green'
          });
        });
      }

      if (savePromise) { // defensive
        savePromise
        .then(function () {
          resetForm();
          $state.reload('orgProfile');
        })
        ['catch'](function (error) {
          switch (error) {
            default:
              modalService.openGenericErrorModal();
          }
          $scope.disableSaveButton = false;
        })
        ['finally'](function () {
          $window.removeEventListener('beforeunload', onBeforeUnload);
        });
      }
    };

    $scope.trackClick = function(action) {
      $scope.$emit('trackOrgProfileEvent', 'click', { type: $scope.trackingType, action: action });
    };

    $scope.trackApplyCodeClick = function () {
      $scope.$emit('trackOrgProfileEvent', 'click', { type: 'org_profile:settings:earn_credits', action: 'redeem_credit:apply_referral_code' });
    };

    function onBeforeUnload(e) {
      var message = 'We\'re still saving your settings. Are you sure you wish to leave this page?';
      (e || $window.event).returnValue = message; // for Firefox
      return message;
    }

    // Doesn't change the select box values, it just tells the form
    // to say it's pristine again while keeping the same values.
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

        if (!$scope.planSelectsForm.$pristine || $scope.plan.newCard) {
          var confirmText = (
            'Are you sure you want to leave?\n' +
            'You haven\'t saved your payment information.\n' +
            'Click cancel to return and save.'
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

    $timeout(function () {
      if ($state.params.upgrade) {
        $scope.changePlanToStandard();
        resetForm();
      }

      $scope.$emit('trackOrgProfileEvent', 'view', {
        type: 'org_profile:settings:payment_plan'
      });
    });
  }
]);
