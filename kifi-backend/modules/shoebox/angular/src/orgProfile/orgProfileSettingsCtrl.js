'use strict';

angular.module('kifi')

.controller('OrgProfileSettingsCtrl', [
  '$window', '$rootScope', '$scope','$timeout', '$state',
  'profileService', 'ORG_PERMISSION',
  function ($window, $rootScope, $scope, $timeout, $state,
            profileService, ORG_PERMISSION) {
    $scope.state = $state;
    $scope.settings = $scope.settings.settings;
    $scope.canExportKeeps = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.EXPORT_KEEPS) !== -1);
    $scope.isAdminExperiment = (profileService.me.experiments.indexOf('admin') !== -1);
    function onHashChange() {
      var anchor = angular.element($window.location.hash.slice(0, -1))[0];
      var headingTop;
      var scrollDestination;

      if (anchor) {
        headingTop = anchor.getBoundingClientRect().top - $window.document.body.getBoundingClientRect().top;
        scrollDestination = headingTop - 70; // make room for header
      } else {
        scrollDestination = 0;
      }

      angular.element('html, body').animate({
        scrollTop: scrollDestination
      });
    }

    $window.addEventListener('hashchange', onHashChange, false);
    $scope.$on('$destroy', function () {
      $window.removeEventListener('hashchange', onHashChange);
    });

    if (!$scope.viewer.membership || $scope.viewer.permissions.indexOf(ORG_PERMISSION.VIEW_SETTINGS) === -1) {
      $rootScope.$emit('errorImmediately');
    }
  }
]);
