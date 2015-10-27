'use strict';

angular.module('kifi')

.directive('kfRedeemCredits', [ 'billingService', '$filter', '$timeout',
  function (billingService, $filter, $timeout) {


    return {
      restrict: 'A',
      templateUrl: 'teamSettings/redeemCredits.tpl.html',
      scope: {
        profile: '=',
        standalone: '='
      },
      link: function($scope) {

        $scope.$error = {};
        $scope.creditRedeemed = 0;

        $scope.applyReferralCode = function (code) {
          $scope.creditRedeemed = 0;
          billingService
            .applyReferralCode($scope.profile.id, code)
            .then(function (response) {
              $scope.$error = {};
              $scope.showCredit = true;
              $scope.creditRedeemed = response.data.value;
              $timeout(function () {
                $scope.showCredit = false;
              }, 3000);
            })
            ['catch'](function (response) {
              var error = response.data && response.data.error;
              switch (error) {
                case 'code_nonexistent':
                  $scope.$error.general = 'Referral code doesn\'t exist';
                  break;
                case 'code_invalid':
                  $scope.$error.general = 'You can\'t redeem your own code';
                  break;
                case 'code_already_used':
                  $scope.$error.general = 'Referral code has already been used';
                  break;
                case 'no_paid_account':
                  $scope.$error.general = 'Account not found. Contact support@kifi.com.';
                  break;
                case 'unrepeatable_reward':
                  $scope.$error.general = 'You\'ve already redeemed this reward';
                  break;
                default:
                  $scope.$error.general = 'Please try again later.';
                  break;
              }
            });
        };
      }
    };
  }
]);
