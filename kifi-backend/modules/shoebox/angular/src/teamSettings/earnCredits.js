'use strict';

angular.module('kifi')

.controller('EarnCreditsCtrl', [
  '$scope', 'billingService', '$timeout',
  function ($scope, billingService, $timeout) {
    billingService.getReferralCode($scope.profile.id).then(function(response) { $scope.referralCode = response.code; });
    $scope.redeemCode = '';

    $scope.copied = false;
    $scope.showCopied = function () {
      $scope.copied = true;
      $timeout(function() { $scope.copied = false; }, 2000);
    };
  }
]);
