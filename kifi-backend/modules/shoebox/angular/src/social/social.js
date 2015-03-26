'use strict';

angular.module('kifi')

.directive('kfSocialConnectNetworks', [
  'socialService',
  function (socialService) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'social/connectNetworks.tpl.html',
      link: function (scope) {
        scope.facebook = socialService.facebook;
        scope.linkedin = socialService.linkedin;
        scope.gmail = socialService.gmail;
        scope.twitter = socialService.twitter;
        scope.expiredTokens = socialService.expiredTokens;
        scope.connectFacebook = socialService.connectFacebook;
        scope.connectLinkedIn = socialService.connectLinkedIn;
        scope.connectTwitter = socialService.connectTwitter;
        scope.importGmail = socialService.importGmail;

        scope.isRefreshingSocialGraph = socialService.isRefreshingSocialGraph;
        scope.refreshingGraphs = socialService.refreshingGraphs;

        scope.facebookStatus = function () {
          if (scope.refreshingGraphs.network.facebook) {
            return 'refreshing';
          } else if (scope.expiredTokens.facebook) {
            return 'expired';
          }
          return 'good';
        };

        scope.linkedinStatus = function () {
          if (scope.refreshingGraphs.network.linkedin) {
            return 'refreshing';
          } else if (scope.expiredTokens.linkedin) {
            return 'expired';
          }
          return 'good';
        };

        scope.twitterStatus = function () {
          if (scope.refreshingGraphs.network.twitter) {
            return 'refreshing';
          } else if (scope.expiredTokens.twitter) {
            return 'expired';
          }
          return 'good';
        };

        socialService.refresh();

      }
    };
  }
]);

