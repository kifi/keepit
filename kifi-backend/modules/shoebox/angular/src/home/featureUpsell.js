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
        var hasFeatureUpsellExp = (profileService.me.experiments || []).indexOf('slack_upsell_widget') !== -1;
        scope.userLoggedIn = $rootScope.userLoggedIn;

        (Object.keys(profileService.prefs).length === 0 ? profileService.fetchPrefs() : $q.when(profileService.prefs)).then(function(prefs){
          scope.showFeatureUpsell = hasFeatureUpsellExp && prefs.slack_upsell_widget;
        });

        scope.hide = function () {
          scope.showFeatureUpsell = false;
          profileService.savePrefs({ slack_upsell_widget: false });
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
