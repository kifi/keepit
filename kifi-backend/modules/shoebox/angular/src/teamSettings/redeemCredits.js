'use strict';

angular.module('kifi')

.directive('kfRedeemCredits', [ 'billingService', '$filter', '$log',
  function (billingService, $filter, $log) {


    return {
      restrict: 'A',
      templateUrl: 'teamSettings/redeemCredits.tpl.html',
      scope: {
        profile: '=',
        standalone: '='
      },
      link: function($scope) {
//        $scope.$watch('creditRedeemed', function (oldCredit, newCredit) {
//          $scope.newCredit = newCredit;
//          $scope.creditRedeemed += newCredit;
//        });

        $scope.$error = {};
        $scope.creditRedeemed = 0;
        $scope.newCredit = 0;

        var counter = 1;

        $scope.applyReferralCode = function (code) {
          $scope.creditRedeemed = 0;
          billingService
            .applyReferralCode($scope.profile.id, code)
            .then(function (response) {
              $log.log(response);
              if (counter < 3) {
                $scope.creditRedeemed = response.creditAdded;
              } else {
                $scope.creditRedeemed = 0;
                $scope.$error.invalid = true;
              }
              counter++;
            })
            ['catch'](function (error) {
              $scope.$error.invalid = true;
              $log.log('this');
            });
        };
      }
    };
  }
]);
