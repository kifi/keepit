'use strict';

angular.module('kifi')

.controller('OrgProfileSlackKeepCtrl', [
  '$scope', '$state', '$stateParams', 'keepActionService',
  function ($scope, $state, $stateParams, keepActionService) {
    $scope.keep = null;
    $scope.user = null;
    $scope.library = null;

    keepActionService
    .getFullKeepInfo($stateParams.keepId, $stateParams.authToken)
    .then(function (keep) {
      $scope.keep = keep;
      $scope.user = keep.user;
      $scope.library = keep.library;
    })
    ['catch'](function () {
      $state.go('orgProfile.slack.basic');
    });
  }
]);
