'use strict';

angular.module('kifi')

.controller('OrgProfileSlackCtrl', [
  '$window', '$location', '$rootScope', '$analytics', '$scope', '$stateParams',
  'profile', 'orgProfileService', 'originTrackingService',
  function ($window, $location, $rootScope, $analytics, $scope, $stateParams,
            profile, orgProfileService, originTrackingService) {
    $window.document.title = profile.organization.name + ' • Kifi';
    $scope.userLoggedIn = $rootScope.userLoggedIn;

    function trackPageView(attributes) {
      var url = $analytics.settings.pageTracking.basePath + $location.url();

      attributes = _.extend(orgProfileService.getCommonTrackingAttributes($scope.profile), attributes);
      attributes = originTrackingService.applyAndClear(attributes);
      if ($scope.viewer.membership) {
        attributes.orgMemberStatus = $scope.viewer.membership.role;
      } else if ($scope.viewer.invite) {
        attributes.orgMemberStatus = 'pendingMember';
      }
      $analytics.pageTrack(url, attributes);
    }

    $scope.slackTeamId = $stateParams.slackTeamId;

    $scope.linkSlack = function (e) {
      e.target.href = 'https://www.kifi.com/link/slack?slackTeamId=' + $scope.slackTeamId;
    };

    //
    // Watches and listeners.
    [
      $rootScope.$on('trackOrgProfileEvent', function (e, eventType, attributes) {
        if (eventType === 'click') {
          if (!$rootScope.userLoggedIn) {
            attributes.type = attributes.type || 'orgProfileLanding';
            orgProfileService.trackEvent('visitor_clicked_page', $scope.profile, attributes);
          } else {
            attributes.type = attributes.type || 'orgProfile';
            orgProfileService.trackEvent('user_clicked_page', $scope.profile, attributes);
          }
        } else if (eventType === 'view') {
          trackPageView(attributes);
        }
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

  }
]);
