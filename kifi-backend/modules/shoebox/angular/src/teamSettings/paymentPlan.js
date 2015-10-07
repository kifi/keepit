'use strict';

angular.module('kifi')

.controller('PaymentPlanCtrl', [
  '$rootScope', '$scope', 'stripeCheckoutService',
  function ($rootScope, $scope, StripeCheckout) {
    var handler = StripeCheckout.configure({
      key: 'pk_test_6pRNASCoBOKtIshFeQd4XMUh',
      image: '/img/documentation/checkout/marketplace.png',
      locale: 'auto',
      token: function (token) {
        // Use the token to create the charge with a server-side script.
        // You can access the token ID with `token.id`
      }
    });

    $scope.openStripeCheckout = function (e) {
      // Open Checkout with further options
      handler.open({
        name: 'Stripe.com',
        description: '2 widgets',
        amount: 2000
      });
      e.preventDefault();
    };

    // Close Checkout on page navigation
    $rootScope.$on('$stateChangeStart', function () {
      handler.close();
    });
  }
]);
