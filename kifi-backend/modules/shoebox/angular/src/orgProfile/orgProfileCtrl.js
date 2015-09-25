'use strict';

angular.module('kifi')

.controller('OrgProfileCtrl', [
  '$window', '$rootScope', '$scope', '$analytics', '$state', '$location', '$log', 'profile',
  'orgProfileService', 'originTrackingService', 'settings',
  function ($window, $rootScope, $scope, $analytics, $state, $location, $log, profile,
            orgProfileService, originTrackingService, settings) {
    $window.document.title = profile.organization.name + ' • Kifi';
    $scope.profile = _.cloneDeep(profile.organization);
    $scope.membership = _.cloneDeep(profile.membership);
    $scope.settings = _.cloneDeep(settings);
  function trackPageView(attributes) {
    var url = $analytics.settings.pageTracking.basePath + $location.url();

    attributes = _.extend(orgProfileService.getCommonTrackingAttributes($scope.profile), attributes);
    attributes = originTrackingService.applyAndClear(attributes);
    if ($scope.membership.role) {
      attributes.orgMemberStatus = $scope.membership.role;
    } else if ($scope.membership.invite) {
      attributes.orgMemberStatus = 'pendingMember';
    }
    $analytics.pageTrack(url, attributes);
  }

  //
  // Watches and listeners.
  //

  $scope.$on('parentOpenInviteModal', function () {
    if ($state.current.name !== 'orgProfile.members') {
      $state.go('orgProfile.members', { openInviteModal: true });
    } else {
      $scope.$broadcast('childOpenInviteModal');
    }
  });

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
