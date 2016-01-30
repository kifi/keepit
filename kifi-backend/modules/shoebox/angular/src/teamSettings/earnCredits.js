'use strict';

angular.module('kifi')

.controller('EarnCreditsCtrl', [
  '$scope', '$timeout', 'billingService', 'profileService', 'ORG_PERMISSION', '$FB', '$twitter', 'URI',
  function ($scope, $timeout, billingService, profileService, ORG_PERMISSION, $FB, $twitter, URI) {
    $scope.redeemCode = '';
    $scope.trackingType = 'org_profile:settings:earn_credits';
    $scope.ORG_PERMISSION = ORG_PERMISSION;

    $scope.hasPermission = function () {
      return $scope.viewer.permissions.indexOf(ORG_PERMISSION.MANAGE_PLAN) > -1;
    };

    $scope.showCopied = function (which) {
      if (which === 'link') {
        $scope.copiedLink = true;
      } else {
        $scope.copiedCode = true;
      }
      trackCodeCopied();
      $timeout(function() { $scope.copiedCode = $scope.copiedLink = false; }, 2000);
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
      $scope.referralUrl = 'https://www.kifi.com/join/' + response.code;
      $scope.referralUrlEncoded = encodeURIComponent($scope.referralUrl);
    });

    $scope.shareFB = function () {
      $FB.ui({
        method: 'feed',
        link: $scope.referralUrl,
        caption: 'Kifi.com',
        description: 'Try Kifi for Teams for free. Using this link, you’ll get $100 credit towards upgrading your team account.',
        ref: 'team_credit',
        name: 'Kifi: Knowledge Management for Teams',
        picture: 'https://djty7jcqog9qu.cloudfront.net/oa/235945e972d48f7a96254e36c50f143b_200x200_c.png'
      });
    };

    $scope.shareTwitter = function (event) {
      event.target.href = 'https://twitter.com/intent/tweet' + URI.formatQueryString({
        original_referer: $scope.referralUrl,
        text: 'Try @Kifi to manage your team’s knowledge and get $100 with code ' + $scope.referralCode,
        tw_p: 'tweetbutton',
        url: $scope.referralUrl
      });
    };

    $timeout(function () {
      // timeout just to let the page render first.
      $twitter.load();
      $FB.init();
    }, 100);
  }
]);
