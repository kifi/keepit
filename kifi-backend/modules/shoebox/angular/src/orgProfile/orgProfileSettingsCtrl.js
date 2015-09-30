'use strict';

angular.module('kifi')

.controller('OrgProfileSettingsCtrl', [
  '$window', '$scope', '$timeout', 'settings', 'ORG_PERMISSION',
  function ($window, $scope, $timeout, settings, ORG_PERMISSION) {
    $scope.settings = settings.settings;
    $scope.canExportKeeps = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.EXPORT_KEEPS) !== -1);
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
  }
]);
