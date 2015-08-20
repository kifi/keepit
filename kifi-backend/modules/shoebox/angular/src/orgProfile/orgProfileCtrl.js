'use strict';

angular.module('kifi')

.controller('OrgProfileCtrl', [
  '$window', '$rootScope', '$scope', '$analytics', '$state', '$location', '$log', 'profile',
  'orgProfileService', 'originTrackingService',
  function ($window, $rootScope, $scope, $analytics, $state, $location, $log, profile,
  orgProfileService, originTrackingService) {
    $window.document.title = profile.organization.name + ' • Kifi';
    $scope.profile = _.cloneDeep(profile.organization);
    $scope.membership = _.cloneDeep(profile.membership);

  function trackPageView(attributes) {
    var url = $analytics.settings.pageTracking.basePath + $location.url();

    attributes = _.extend(orgProfileService.getCommonTrackingAttributes($scope.profile), attributes);
    attributes = originTrackingService.applyAndClear(attributes);
    attributes.orgMemberStatus = $scope.membership.role || ($scope.membership.invite ? 'pendingMember' : null);
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
