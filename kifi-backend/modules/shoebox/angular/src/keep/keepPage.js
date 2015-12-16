'use strict';

angular.module('kifi')

.controller('KeepPageCtrl', [ '$rootScope', '$scope', '$stateParams', 'keepActionService',
  function ($rootScope, $scope, $stateParams, keepActionService) {
    keepActionService.getFullKeepInfo($stateParams.pubId).then(function (result) {
      $scope.loaded = true;
      $scope.keep = result;
    })['catch'](function(reason){
      $scope.loaded = true;
      $rootScope.$emit('errorImmediately', reason);
    });
  }
]);
