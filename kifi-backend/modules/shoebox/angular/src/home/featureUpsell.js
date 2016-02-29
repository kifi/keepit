'use strict';


angular.module('kifi')

.directive('kfFeatureUpsell', [
  '$location', '$window', '$rootScope', '$state', '$analytics', '$q', 'profileService',
  function($location, $window, $rootScope, $state, $analytics, $q, profileService) {

    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'home/featureUpsell.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;
        var hasFeatureUpsellExp = ((profileService.me.experiments || []).indexOf('slack_upsell_widget') !== -1);
        scope.userLoggedIn = $rootScope.userLoggedIn;

        var slackIntPromoP;
        if (Object.keys(profileService.prefs).length === 0 ) {
          slackIntPromoP = profileService.fetchPrefs().then(function(prefs) {
            return prefs.slack_int_promo;
          });
        } else {
          slackIntPromoP = $q.when(profileService.prefs.slack_int_promo);
        }
        slackIntPromoP.then(function(showPromo) {
          scope.showFeatureUpsell = hasFeatureUpsellExp && showPromo;
        });

        scope.hide = function () {
          scope.showFeatureUpsell = false;
          profileService.savePrefs({ slack_int_promo: false });
        };

        scope.clickedConnectSlack = function() {
          $analytics.eventTrack('user_clicked_page', { type: 'homeFeed', action: 'slackSyncAllChannels' });
        };

        scope.clickedLearnMore = function() {
          $analytics.eventTrack('user_clicked_page', { type: 'homeFeed', action: 'slackLearnMore' });
        };
      }
    };
  }]
);
