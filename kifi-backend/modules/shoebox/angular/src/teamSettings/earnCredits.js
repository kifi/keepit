'use strict';

angular.module('kifi')

.controller('EarnCreditsCtrl', [
  '$scope', 'billingService', '$timeout', 'ORG_PERMISSION',
  function ($scope, billingService, $timeout, ORG_PERMISSION) {
    $scope.redeemCode = '';
    $scope.trackingType = 'org_settings:earn_credits';
    $scope.ORG_PERMISSION = ORG_PERMISSION;
    $scope.hasPermission = function () {
      return $scope.viewer.permissions.indexOf(ORG_PERMISSION.MANAGE_PLAN) > -1;
    };

    $scope.copied = false;
    $scope.showCopied = function () {
      trackCodeCopied();
      $scope.copied = true;
      $timeout(function() { $scope.copied = false; }, 2000);
    };

    function trackCodeCopied() {
      $scope.$emit('trackOrgProfileEvent', 'click', { type: $scope.trackingType, action: 'copy_referral_code' });
    }

    $scope.trackApplyCodeClick = function () {
      $scope.$emit('trackOrgProfileEvent', 'click', { type: 'org_profile:settings:earn_credits', action: 'redeem_credit:apply_referral_code' });
    };

    $scope.$emit('trackOrgProfileEvent', 'view', { type: 'org_profile:settings:earn_credits' });

    billingService.getReferralCode($scope.profile.id)
    .then(function(response) {
      $scope.referralCode = response.code;
    });
  }
]);
