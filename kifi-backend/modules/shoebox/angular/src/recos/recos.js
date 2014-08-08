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

    $scope.weights = $scope.weights || {
      socialScore: 7,
      recencyScore: 0.5,
      popularityScore: 1,
      overallInterestScore: 8,
      recentInterestScore: 4,
      priorScore: 1,
      rekeepScore: 1,
      discoveryScore: 1
    };

    $scope.reload = function () {
      recoService.fetchAdHocRecos($scope.weights);
    };

    recoService.fetchAdHocRecos($scope.weights);

  }
]);
