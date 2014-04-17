'use strict';

angular.module('kifi.profileCard', ['kifi.profileService'])

.directive('kfProfileCard', [
  'profileService', '$analytics',
  function (profileService, $analytics) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'profileCard/profileCard.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.me = profileService.me;

        profileService.getMe();

        scope.data = scope.data || {};
        scope.openHelpRankHelp = function () {
          scope.data.showHelpRankHelp = true;
          $analytics.eventTrack('user_viewed_page', {
            'type': 'HelpRankHelp'
          });
        };

        scope.yesLikeHelpRank = function () {
          scope.data.showHelpRankHelp = false;
          $analytics.eventTrack('user_clicked_page', {
            'action': 'yesLikeHelpRank'
          });
        };

        scope.noLikeHelpRank = function () {
          scope.data.showHelpRankHelp = false;
          $analytics.eventTrack('user_clicked_page', {
            'action': 'noLikeHelpRank'
          });
        };


      }
    };
  }
]);
