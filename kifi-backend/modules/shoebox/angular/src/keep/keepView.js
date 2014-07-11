'use strict';

angular.module('kifi.keepView', [])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/keep/:keepId', {
      templateUrl: 'keep/keepView.tpl.html',
      controller: 'KeepViewCtrl'
    });
  }
])

.controller('KeepViewCtrl', [
  '$scope', '$routeParams', 'keepService',
  function ($scope, $routeParams, keepService) {

    $scope.keeps = [];
    $scope.loading = true;
    $scope.keepService = keepService;
    var keepId = $routeParams.keepId || '';

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      } else {
        return 'Showing 1 keep';
      }
    };

    function initKeepList() {
      keepService.getSingleKeep(keepId).then(function () {
        $scope.keeps = keepService.list;
        $scope.loading = false;
      });
    }

    $scope.$watch('keepService.seqReset()', function () {
      initKeepList();
    });

  }
]);
