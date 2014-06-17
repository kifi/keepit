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
    var keepId = $routeParams.keepId || '';
    keepService.fetchKeepInfo(keepId).then(function (keep) {
      $scope.keeps = [keep];
      $scope.loading = false;
    });

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      } else {
        return 'Showing 1 keep';
      }
    };
  }
]);
