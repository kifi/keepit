'use strict';

angular.module('kifi.helprank', ['util', 'kifi.keepService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/helprank/:helprank', {
      templateUrl: 'helprank/helprank.tpl.html',
      controller: 'HelpRankCtrl'
    });
  }
])

.controller('HelpRankCtrl', [
  '$scope', 'keepService', '$routeParams', '$window',
  function ($scope, keepService, $routeParams, $window) {

    keepService.reset();
    $scope.keepService = keepService;
    $scope.keeps = keepService.list;

    var helprank = $routeParams.helprank || '';

    $scope.hasMore = function () {
      return !keepService.isEnd();
    };
    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      }

      var subtitle = keepService.getSubtitle($scope.mouseoverCheckAll);
      if (subtitle) {
        return subtitle;
      }

      var numShown = $scope.keeps.length;
      switch (numShown) {
      case 0:
        return 'No Keeps';
      case 1:
        return 'Showing the only Keep';
      case 2:
        return 'Showing both Keeps';
      }
      if (keepService.isEnd()) {
        return 'Showing all ' + numShown + ' Keeps';
      }
      return 'Showing the ' + numShown + ' latest Keeps';
    };

    $scope.scrollDistance = '100%';
    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;
      return keepService.getKeepsByHelpRank(helprank).then(function (list) {
        $scope.loading = false;

        if (keepService.isEnd()) {
          $scope.scrollDisabled = true;
        }

        return list;
      });
    };

    $scope.getNextKeeps();

  }
]);
