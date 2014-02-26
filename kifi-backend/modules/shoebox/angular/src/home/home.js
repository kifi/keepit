'use strict';

angular.module('kifi.home', ['util', 'kifi.keepService'])

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
  '$scope', 'tagService', 'keepService', '$q',
  function ($scope, tagService, keepService, $q) {
    $scope.toggleSelectAll = keepService.toggleSelectAll;
    $scope.isSelectedAll = keepService.isSelectedAll;
    $scope.keepService = keepService;
    $scope.keeps = keepService.list;

    $scope.loadingKeeps = true;

    var promise = keepService.getList().then(function (list) {
      $scope.loadingKeeps = false;
      return list;
    });

    $q.all([promise, tagService.fetchAll()]).then(function () {
      $scope.loadingKeeps = false;
    });

    $scope.checkEnabled = true;

    $scope.page = {
      title: 'Browse your Keeps'
    };

    $scope.mouseoverCheckAll = false;

    $scope.onMouseoverCheckAll = function () {
      $scope.mouseoverCheckAll = true;
    };

    $scope.onMouseoutCheckAll = function () {
      $scope.mouseoverCheckAll = false;
    };

    $scope.getSubtitle = function () {
      var selectedCount = keepService.getSelectedLength(),
        numShown = $scope.keeps && $scope.keeps.length || 0;

      if ($scope.mouseoverCheckAll) {
        if (selectedCount === numShown) {
          return 'Deselect all ' + numShown + ' Keeps below';
        }
        return 'Select all ' + numShown + ' Keeps below';
      }

      switch (selectedCount) {
      case 0:
        break;
      case 1:
        return selectedCount + ' Keep selected';
      default:
        return selectedCount + ' Keeps selected';
      }

      switch (numShown) {
      case 0:
        return 'You have no Keeps';
      case 1:
        return 'Showing your only Keep';
      case 2:
        return 'Showing both of your Keeps';
      default:
        /*
        if (numShown === $scope.results.numTotal) {
          return 'Showing all ' + numShown + ' of your Keeps';
        }
        */
        return 'Showing your ' + numShown + ' latest Keeps';
      }
    };

    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loadingKeeps) {
        return $q.when([]);
      }

      $scope.loadingKeeps = true;

      return keepService.getList().then(function (list) {
        $scope.loadingKeeps = false;
        //$scope.refreshScroll();
        return list;
      });
    };
  }
]);
