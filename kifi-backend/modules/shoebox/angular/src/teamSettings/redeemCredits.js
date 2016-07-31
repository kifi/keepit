'use strict';

angular.module('kifi')

.directive('kfRedeemCredits', [
  'billingService', '$timeout', 'profileService',
  function (billingService, $timeout, profileService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'teamSettings/redeemCredits.tpl.html',
      scope: {
        profile: '=',
        standalone: '=',
        onApply: '=',
        autofocus: '='
      },
      link: function ($scope, $element) {
        $scope.$error = {};
        $scope.creditRedeemed = 0;

        $scope.$watch(function () {
          return profileService.prefs.stored_credit_code;
        }, function () {
          if (profileService.prefs.stored_credit_code) {
            $scope.redeemCode = profileService.prefs.stored_credit_code;
          }
        });

        if ($scope.autofocus) {
          $timeout(function() {
              $element.find('.kf-redeem-credits-box').focus();
          });
        }

        $scope.applyReferralCode = function (code) {
          $scope.onApply();
          $scope.creditRedeemed = 0;
          billingService
            .applyReferralCode($scope.profile.id, code)
            .then(function (response) {
              profileService.fetchPrefs(true);
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
                  $scope.$error.general = 'You\'ve already redeemed a similar promotion.';
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
