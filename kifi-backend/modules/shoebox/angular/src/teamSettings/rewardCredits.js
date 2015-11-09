'use strict';

angular.module('kifi')

.directive('kfRewardCredits', [
  'billingService',
  function (billingService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'teamSettings/rewardCredits.tpl.html',
      scope: {
        profile: '='
      },
      link: function ($scope) {
        $scope.rewards = null;

        var CATEGORY_MAP = {
          'keeps_and_libraries': 'Keeps and libraries',
          'org_information': 'Team information',
          'org_membership': 'Team membership',
          'referrals': 'Referrals',
          'credit_codes': 'Credit codes'
        };

        $scope.getCategoryFromKey = function (key) {
          return CATEGORY_MAP[key];
        };

        billingService
        .getRewards($scope.profile.id)
        .then(function (rewardsData) {
          $scope.rewards = rewardsData.rewards;
        });
      }
    };
  }
]);
