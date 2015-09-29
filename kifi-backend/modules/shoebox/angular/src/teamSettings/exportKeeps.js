'use strict';

angular.module('kifi')

.controller('ExportKeepsCtrl', [
  '$scope',
  function ($scope) {
    $scope.exportState = {
      format: 'html'
    };
  }
]);
