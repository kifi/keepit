'use strict';

angular.module('kifi')

.controller('ExportKeepsCtrl', [
  '$window', '$scope', 'orgProfileService', 'modalService',
  function ($window, $scope, orgProfileService, modalService) {
    $scope.exportState = {
      format: 'html'
    };

    $scope.exportKeeps = function () {
      var format = $scope.exportState.format;

      orgProfileService
      .exportOrgKeeps(format, [ $scope.profile.id ])
      ['catch'](modalService.openGenericErrorModal);
    };
  }
]);
