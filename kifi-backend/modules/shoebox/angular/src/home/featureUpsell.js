'use strict';


angular.module('kifi')

.directive('kfFeatureUpsell', [
  '$location', '$window', '$rootScope', '$state', '$analytics', '$q', 'profileService', 'slackService', 'messageTicker',
  function($location, $window, $rootScope, $state, $analytics, $q, profileService, slackService, messageTicker) {

    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'home/featureUpsell.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;
        var hasFeatureUpsellExp = (scope.me.experiments || []).indexOf('slack_upsell_widget') !== -1;
        var orgToSync = scope.me.orgs.filter(function (org) {
          return !org.slackTeam;
        })[0];
        scope.userLoggedIn = $rootScope.userLoggedIn;

        (Object.keys(profileService.prefs).length === 0 ? profileService.fetchPrefs() : $q.when(profileService.prefs)).then(function (prefs) {
          scope.showFeatureUpsell = orgToSync && hasFeatureUpsellExp && prefs.slack_upsell_widget;
        });

        scope.hide = function () {
          scope.showFeatureUpsell = false;
          profileService.savePrefs({ slack_upsell_widget: false });
        };

        scope.clickedConnectSlack = function() {
          $analytics.eventTrack('user_clicked_page', { type: 'homeFeed', action: 'slackSyncAllChannels' });
          slackService.publicSync(orgToSync.id).then(function (resp) {
            if (resp.redirect) {
              $window.location = resp.redirect;
            } else {
              messageTicker({ text: 'Oops, that didn’t work. Try again?', type: 'red' });
            }
          });
        };

        scope.clickedLearnMore = function() {
          $analytics.eventTrack('user_clicked_page', { type: 'homeFeed', action: 'slackLearnMore' });
        };
      }
    };
  }]
);
