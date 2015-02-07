'use strict';

angular.module('kifi')

.directive('kfRotatingConnect', ['socialService', 'profileService', function (socialService, profileService) {
  return {
    replace: true,
    restrict: 'A',
    scope: {
      network: '='
    },
    templateUrl: 'friends/rotatingConnect.tpl.html',
    link:  function (scope/*, element, attrs*/) {
      function getEligibleNetworksCsv() {
        var onTwitterExperiment = _.indexOf(profileService.me.experiments, 'twitter_beta') > -1;
        return _.compact([
          socialService.facebook && socialService.facebook.profileUrl ? null : 'Facebook',
          !onTwitterExperiment || (socialService.twitter && socialService.twitter.profileUrl) ? null : 'Twitter',
          socialService.linkedin && socialService.linkedin.profileUrl ? null : 'LinkedIn',
          socialService.gmail && socialService.gmail.length ? null : 'Gmail'
        ]).join(',');
      }

      function chooseNetwork(csv) {
        scope.network = csv ? _.sample(csv.split(',')) : null;
      }

      scope.connectFacebook = socialService.connectFacebook;
      scope.connectTwitter = socialService.connectTwitter;
      scope.connectLinkedIn = socialService.connectLinkedIn;
      scope.importGmail = socialService.importGmail;

      socialService.refresh();
      scope.$watch(getEligibleNetworksCsv, chooseNetwork);
    }
  };
}]);
