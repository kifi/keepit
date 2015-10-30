'use strict';

angular.module('kifi')

.controller('EarnCreditsCtrl', [
  '$scope', 'billingService', '$timeout',
  function ($scope, billingService, $timeout) {
    billingService.getReferralCode($scope.profile.id)
      .then(function(response) {
      $scope.referralCode = response.code;
      });

    $scope.redeemCode = '';
    $scope.trackingType = 'org_settings:earn_credits';

    $scope.copied = false;
    $scope.showCopied = function () {
      trackCodeCopied();
      $scope.copied = true;
      $timeout(function() { $scope.copied = false; }, 2000);
    };

    function trackCodeCopied() {
      $scope.$emit('trackOrgProfileEvent', 'click', { type: $scope.trackingType, action: 'copy_referral_code' });
    }

    $scope.$emit('trackOrgProfileEvent', 'view', { type: 'org_profile:settings:earn_credits' });
  }
]);
