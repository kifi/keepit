'use strict';

angular.module('kifi.recos', [])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/kifeeeed', {
      templateUrl: 'recos/adhoc.tpl.html'
    });
  }
])

.controller('RecoCtrl', [
  '$scope', 'recoService', '$window',
  function ($scope, recoService, $window) {
    $window.document.title = 'Kifi • Your Recommendation List';

    $scope.loading = recoService.loading;

    $scope.keeps = recoService.recos;

    recoService.fetchAdHocRecos();

  }
]);
