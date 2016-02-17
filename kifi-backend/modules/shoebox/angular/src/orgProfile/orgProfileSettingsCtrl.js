'use strict';

angular.module('kifi')

.controller('OrgProfileSettingsCtrl', [
  '$window', '$rootScope', '$scope','$timeout', '$state',
  'profileService', 'orgProfileService', 'ORG_PERMISSION', '$location',
  function ($window, $rootScope, $scope, $timeout, $state,
            profileService, orgProfileService, ORG_PERMISSION, $location) {
    $scope.state = $state;
    $scope.settings = ($scope.settings && $scope.settings.settings) || [];
    $scope.canViewPrivileges = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.VIEW_SETTINGS) !== -1);
    $scope.canExportKeeps = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.EXPORT_KEEPS) !== -1);
    $scope.canManagePlan = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.MANAGE_PLAN) !== -1);
    $scope.canRedeemCredit = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.REDEEM_CREDIT_CODE) !== -1);
    $scope.isAdminExperiment = ((profileService.me.experiments || []).indexOf('admin') !== -1);
    $scope.canEditIntegrations = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.CREATE_SLACK_INTEGRATION) !== -1);

    function onHashChange() {
      var scrollSettingsAnchorRe = /[a-zA-Z0-9-]+/g; // one or more alpha, hyphens (and numbers just to follow POLS)
      var hash = $window.location.hash.slice(0, -1);

      if (!scrollSettingsAnchorRe.test(hash)) {
        return;
      }

      var anchor = angular.element(hash)[0];
      var header = angular.element('.kf-oph')[0];
      var scrollDestination;

      if (anchor) {
        scrollDestination = anchor.getBoundingClientRect().top - (header && header.getBoundingClientRect().top || 0);
        angular.element('#kf-body-container-content, html, body').animate({
          scrollTop: scrollDestination - 16 // the 16 provides padding from the site header
        });
      }
    }

    $window.addEventListener('hashchange', onHashChange, false);
    $scope.$on('$destroy', function () {
      $window.removeEventListener('hashchange', onHashChange);
    });

    if (!$scope.viewer.membership) {
      $rootScope.$emit('errorImmediately');
    }

    var managePlanPages = ['plan', 'activity', 'contacts'];
    var currentPage = $location.path().split('/')[3] && $location.path().split('/')[3].toLowerCase();

    if (managePlanPages.indexOf(currentPage) !== -1 && $scope.viewer.permissions.indexOf(ORG_PERMISSION.MANAGE_PLAN) === -1) {
      $rootScope.$emit('errorImmediately');
    }

    if (currentPage === 'export' && $scope.viewer.permissions.indexOf(ORG_PERMISSION.EXPORT_KEEPS) === -1) {
      $rootScope.$emit('errorImmediately');
    }

    $scope.onClickTrack = function(event, action, type) {
      if (event.which === 1) {
        orgProfileService.trackEvent('user_clicked_page', $scope.profile, { action: action, type: type });
      }
    };

    [
      $rootScope.$on('$stateChangeSuccess', function (event, fromState, fromParams, toState) {
        if (fromState === toState) {
          $state.go($state.current, { '#': 'n-' }); // clear the hash. setting it to empty scrolls to the top
        }
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    $timeout(function () {
      if ($window.location.hash !== '') {
        onHashChange();
      }
    });
  }
]);
