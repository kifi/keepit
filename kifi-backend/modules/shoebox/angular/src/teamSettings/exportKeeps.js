'use strict';

angular.module('kifi')

.controller('ExportKeepsCtrl', [
  '$scope', '$sce', 'routeService', 'ORG_PERMISSION',
  function ($scope, $sce, routeService, ORG_PERMISSION) {
    $scope.exportState = {
      format: 'html'
    };
    $scope.actionUrl = $sce.trustAsResourceUrl(routeService.exportOrganizationKeeps);
    $scope.canExportKeeps = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.EXPORT_KEEPS) !== -1);
    $scope.successMessage = '';

    $scope.submitExportRequest = function () {
      $scope.successMessage = 'You can find the file in your downloads folder.';
    };
  }
]);
