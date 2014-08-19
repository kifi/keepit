'use strict';

angular.module('kifi')

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
      socialScore: 5,
      recencyScore: 1,
      popularityScore: 1,
      overallInterestScore: 6,
      recentInterestScore: 9,
      priorScore: 2,
      rekeepScore: 6,
      discoveryScore: 3
    };

    $scope.reload = function () {
      recoService.fetchAdHocRecos($scope.weights);
    };

    recoService.fetchAdHocRecos($scope.weights);

  }
]);
