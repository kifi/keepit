'use strict';

angular.module('kifi')

.controller('OrgProfileCtrl', [
  '$window', '$rootScope', '$scope', '$analytics', '$location', 'profile',
  'orgProfileService', 'originTrackingService', 'profileService',
  function ($window, $rootScope, $scope, $analytics, $location, profile,
  orgProfileService, originTrackingService, profileService) {
    $window.document.title = profile.organization.name + ' • Kifi';
    $scope.profile = _.cloneDeep(profile.organization);
    $scope.membership = _.cloneDeep(profile.membership);

  function trackPageView(attributes) {
    var url = $analytics.settings.pageTracking.basePath + $location.url();

    attributes = _.extend(orgProfileService.getCommonTrackingAttributes($scope.profile), attributes);
    attributes = originTrackingService.applyAndClear(attributes);
    if ($rootScope.userLoggedIn) {
      attributes.owner = $scope.profile.owner.id === profileService.me.id;
    }
    $analytics.pageTrack(url, attributes);
  }

  //
  // Watches and listeners.
  //

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
