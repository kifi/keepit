'use strict';

angular.module('kifi')

.controller('OrgProfileCtrl', [
  '$window', '$rootScope', '$scope', '$analytics', '$state', '$stateParams',
  '$location', 'profile', 'orgProfileService', 'originTrackingService',
  function ($window, $rootScope, $scope, $analytics, $state, $stateParams,
            $location, profile, orgProfileService, originTrackingService) {
    $window.document.title = profile.organization.name + ' • Kifi';
    $scope.profile = _.cloneDeep(profile.organization);
    $scope.viewer = profile.viewer;
    $scope.settings = profile.organization.config;
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

  if ($stateParams.signUpWithSlack === 'welcome') {
    $state.go('orgProfile.slack.welcome', $stateParams);
  } else if ($stateParams.signUpWithSlack === 'keep'){
    $state.go('orgProfile.slack.keep', $stateParams);
  } else if ($stateParams.signUpWithSlack === '' || $stateParams.signUpWithSlack){
    $state.go('orgProfile.slack.basic', $stateParams);
  }

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
