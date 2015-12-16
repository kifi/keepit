'use strict';


angular.module('kifi')

.directive('kfFeatureUpsell', [ '$window', '$state', '$analytics', 'profileService', 'libraryService',
  function($window, $state, $analytics, profileService, libraryService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'home/featureUpsell.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;
        scope.showFeatureUpsell = (scope.me.orgs || []).length < 2;
        scope.clickedGetStarted = function() {
          $analytics.eventTrack('user_clicked_page', { type: 'homeFeed', action: 'slackGetStarted' });
          if (scope.me.orgs.length > 0) {
            var org = scope.me.orgs[0];
            libraryService.getLibraryByHandleAndSlug(org.handle, 'general', '', false).then(function(library){
              if (library.slack && library.slack.integrations && library.slack.integrations.length > 0) {
                $state.go('library.keeps', { handle: org.handle, librarySlug: 'general', 'showSlackDialog': true });
              } else {
                $window.location = library.slack && (library.slack.link || '').replace('search%3Aread%2Creactions%3Awrite', '');
              }
            });
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
