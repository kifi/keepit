'use strict';


angular.module('kifi')

.directive('kfFeatureUpsell', [
  '$window', '$rootScope', '$state', '$analytics', '$q', 'profileService', 'libraryService',
  function($window, $rootScope, $state, $analytics, $q, profileService, libraryService) {

    function getMyGeneralLibrary(me) {
      var org = me.orgs[0];
      if (org) {
        return libraryService.getLibraryByHandleAndSlug(org.handle, 'general', '', false);
      } else {
        return $q.reject();
      }
    }

    function navigateIfIntegrationsExist(library, navigateCb) {
      var org = library.org;
      var integrations = (library.slack && library.slack.integrations);
      var navigateUrl = (library.slack && (library.slack.link || '').replace('search%3Aread%2Creactions%3Awrite', ''));

      if (integrations.length > 0) {
        $state.go('library.keeps', { handle: org.handle, librarySlug: 'general', 'showSlackDialog': true });
      } else {
        navigateCb(navigateUrl);
      }
    }

    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'home/featureUpsell.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;
        scope.showFeatureUpsell = (scope.me.orgs || []).length === 0;
        scope.userLoggedIn = $rootScope.userLoggedIn;
        scope.generalLibrary = null;
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

        var generalLibraryPromise = getMyGeneralLibrary(scope.me);
        generalLibraryPromise.then(function (generalLib) {
          scope.generalLibrary = generalLib;
        });

        scope.clickedGetStarted = function($event) {
          $analytics.eventTrack('user_clicked_page', { type: 'homeFeed', action: 'slackGetStarted' });
          var org = scope.me.orgs[0];
          if (scope.me.orgs.length > 0 && org) {
            if (scope.generalLibrary) {
              // it's already loaded, so we can use the user's click to open a new tab
              // without hitting the popup blockers
              navigateIfIntegrationsExist(scope.generalLibrary, function (navigateUrl) {
                $event.target.target = '_blank';
                $event.target.href = navigateUrl;
              });
            } else {
              // wait for it to load, and then open in this tab
              // because the click event probably won't be in the stack
              // any more by the time it's loaded
              generalLibraryPromise.then(function (library) {
                navigateIfIntegrationsExist(library, function (navigateUrl) {
                  $window.location = navigateUrl;
                });
              });
            }
          } else {
            $state.go('teams.new', { showSlackPromo: true });
          }
        };
        scope.clickedLearnMore = function() {
          $analytics.eventTrack('user_clicked_page', { type: 'homeFeed', action: 'slackLearnMore' });
        };
      }
    };
  }]
);
