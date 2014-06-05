'use strict';

angular.module('kifi.home', ['util', 'kifi.keepService', 'kifi.modal'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/', {
      templateUrl: 'home/home.tpl.html',
      controller: 'HomeCtrl'
    });
  }
])

.controller('HomeCtrl', [
  '$scope', 'tagService', 'keepService', '$q', '$timeout', '$window',
  function ($scope, tagService, keepService, $q, $timeout, $window) {
    keepService.reset();

    $window.document.title = 'Kifi • Your Keeps';

    $scope.keepService = keepService;
    $scope.keeps = keepService.list;
    $scope.enableSearch();

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
        return 'You have no keeps';
      case 1:
        return 'Showing your only keep';
      case 2:
        return 'Showing both of your keeps';
      default:
        if (keepService.isEnd()) {
          return 'Showing all ' + numShown + ' of your keeps';
        }
        return 'Showing your ' + numShown + ' latest keeps';
      }
    };

    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return $q.when([]);
      }

      $scope.loading = true;

      return keepService.getList().then(function (list) {
        $scope.loading = false;

        if (keepService.isEnd()) {
          $scope.scrollDisabled = true;
        }

        return list;
      });
    };

    $scope.getNextKeeps().then(function () {
      return $scope.getNextKeeps();
    });
  }
]);
