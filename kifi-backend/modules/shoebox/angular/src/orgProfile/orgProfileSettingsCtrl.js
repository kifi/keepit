'use strict';

angular.module('kifi')

.controller('OrgProfileSettingsCtrl', [
  '$window', '$rootScope', '$scope','$timeout', '$state',
  'profileService', 'orgProfileService', 'ORG_PERMISSION', '$location',
  function ($window, $rootScope, $scope, $timeout, $state,
            profileService, orgProfileService, ORG_PERMISSION, $location) {
    $scope.state = $state;
    $scope.settings = $scope.settings.settings;
    $scope.canExportKeeps = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.EXPORT_KEEPS) !== -1);
    $scope.canManagePlan = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.MANAGE_PLAN) !== -1);
    $scope.canRedeemCredit = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.REDEEM_CREDIT_CODE) !== -1);
    $scope.isAdminExperiment = (profileService.me.experiments.indexOf('admin') !== -1);

    function onHashChange() {
      var anchor = angular.element($window.location.hash.slice(0, -1))[0];
      var headingTop;
      var scrollDestination;

      if (anchor) {
        headingTop = anchor.getBoundingClientRect().top - $window.document.body.getBoundingClientRect().top;
        scrollDestination = headingTop - 70; // make room for header

        angular.element('html, body').animate({
          scrollTop: scrollDestination
        });
      }
    }

    $window.addEventListener('hashchange', onHashChange, false);
    $scope.$on('$destroy', function () {
      $window.removeEventListener('hashchange', onHashChange);
    });

    if (!$scope.viewer.membership || $scope.viewer.permissions.indexOf(ORG_PERMISSION.VIEW_SETTINGS) === -1) {
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

    $scope.$on('$viewContentLoaded', function () {
      if ($window.location.hash !== '') {
        onHashChange();
      }
    });
  }
]);
