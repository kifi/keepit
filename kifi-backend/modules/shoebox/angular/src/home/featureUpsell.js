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
        var isAdmin = ((profileService.me.experiments || []).indexOf('admin') !== -1);
        scope.showFeatureUpsell =  isAdmin && (scope.me.orgs || []).filter(function(org) {
            return org.slack && org.slack.slackTeam;
        }).length === 0;
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
          scope.showFeatureUpsell = scope.showFeatureUpsell || showPromo;
        });

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
