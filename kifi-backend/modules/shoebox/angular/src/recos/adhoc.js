'use strict';

angular.module('kifi')

.controller('AdhocCtrl', [
  '$scope', 'adhocService', '$window',
  function ($scope, adhocService, $window) {
    $window.document.title = 'Kifi • Your Recommendation List';

    $scope.loading = adhocService.loading;

    $scope.keeps = adhocService.recos;

    $scope.weights = $scope.weights || {
      socialScore: 5,
      recencyScore: 1,
      popularityScore: 1,
      overallInterestScore: 6,
      recentInterestScore: 9,
      priorScore: 2,
      rekeepScore: 6,
      discoveryScore: 3,
      curationScore: 4
    };

    $scope.reload = function () {
      adhocService.fetchAdHocRecos($scope.weights);
    };

    adhocService.fetchAdHocRecos($scope.weights);

  }
]);
