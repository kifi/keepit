'use strict';

angular.module('kifi')

.controller('EarnCreditsCtrl', [
  '$scope', 'billingService', '$log',
  function ($scope, billingService, $log) {
    $scope.referralCode = billingService.getReferralCode($scope.profile.id);
    $scope.redeemCode = '';

    $scope.copied = false;
    $scope.showCopied = function () {
      $scope.copied = true;
    };
    $scope.applyRedeemCode = function (code) {
      billingService.applyReferralCode($scope.profile.id, code);
    };
  }
]);
