'use strict';

angular.module('kifi')

.controller('ExportKeepsCtrl', [
  '$scope', '$sce', '$analytics', '$timeout', 'routeService', 'ORG_PERMISSION',
  function ($scope, $sce, $analytics, $timeout, routeService, ORG_PERMISSION) {
    $scope.exportState = {
      format: 'html'
    };
    $scope.actionUrl = $sce.trustAsResourceUrl(routeService.exportOrganizationKeeps);
    $scope.canExportKeeps = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.EXPORT_KEEPS) !== -1);
    $scope.exported = false;

    $scope.submitExportRequest = function () {
      $scope.exported = true;
    };

    $timeout(function () {
      $analytics.eventTrack('user_viewed_page', {
        type: 'exportKeeps'
      });
    });
  }
]);
