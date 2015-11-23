'use strict';

angular.module('kifi')

.controller('KeepPageCtrl', [ '$scope', '$stateParams', 'keepActionService',
  function ($scope, $stateParams, keepActionService) {
    keepActionService.getFullKeepInfo($stateParams.pubId).then(function (result) {
      $scope.loaded = true;
      $scope.keep = result;
    });
  }
]);
