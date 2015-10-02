'use strict';

angular.module('kifi')

.controller('OrgProfileSettingsCtrl', [
  '$window', '$rootScope', '$scope','$timeout', '$state', 'settings',
  'profileService', 'ORG_PERMISSION',
  function ($window, $rootScope, $scope, $timeout, $state, settings,
            profileService, ORG_PERMISSION) {
    $scope.state = $state;
    $scope.settings = settings.settings;
    $scope.canExportKeeps = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.EXPORT_KEEPS) !== -1);
    $scope.isAdminExperiment = (profileService.me.experiments.indexOf('admin') !== -1);
    function onHashChange() {
      var anchor = angular.element($window.location.hash.slice(0, -1))[0];

      if (anchor) {
        angular.element('html, body').animate({
          scrollTop: anchor.getBoundingClientRect().top - $window.document.body.getBoundingClientRect().top
        });
      }
    }

    $window.addEventListener('hashchange', onHashChange, false);
    $scope.$on('$destroy', function () {
      $window.removeEventListener('hashchange', onHashChange);
    });

    if (!$scope.viewer.membership || $scope.viewer.membership.role !== 'admin') {
      $rootScope.$emit('errorImmediately');
    }
  }
]);
