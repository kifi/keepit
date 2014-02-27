'use strict';

angular.module('kifi.search', ['util', 'kifi.keepService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/find', {
      templateUrl: 'search/search.tpl.html',
      controller: 'SearchCtrl'
    });
  }
])

.controller('SearchCtrl', [
  '$scope', 'keepService', '$routeParams',
  function ($scope, keepService, $routeParams) {
    keepService.unselectAll();

    var query = $routeParams.q || '',
      filter = $routeParams.f || 'm',
      lastResult = null;

    $scope.keeps = [];

    $scope.results = {
      myTotal: 0,
      friendsTotal: 0,
      othersTotal: 0
    };

    $scope.isFilterSelected = function (type) {
      return filter === type;
    };

    function getFilterCount(type) {
      switch (type) {
      case 'm':
        return $scope.results.myTotal;
      case 'f':
        return $scope.results.friendsTotal;
      case 'a':
        return $scope.results.othersTotal;
      }
    }

    $scope.isEnabled = function (type) {
      if ($scope.isFilterSelected(type)) {
        return false;
      }
      return !!getFilterCount(type);
    };

    $scope.getFilterUrl = function (type) {
      if ($scope.isEnabled(type)) {
        var count = getFilterCount(type);
        if (count) {
          return '/find?q=' + query + '&f=' + type;
        }
      }
      return '';
    };

    $scope.getSubtitle = function () {
      var numShown = $scope.keeps.length;

      if ($scope.loading) {
        return 'Searching...';
      }

      switch (numShown) {
      case 0:
        return 'Sorry, no results found for &#x201c;' + query + '&#x202c;';
      case 1:
        return '1 result found';
      default:
        return 'Top ' + numShown + ' results';
      }
    };

    $scope.scrollDistance = '100%';
    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;
      keepService.find(query, filter, lastResult && lastResult.context).then(function (data) {
        $scope.loading = false;

        $scope.results.myTotal = $scope.results.myTotal || data.myTotal || 0;
        $scope.results.friendsTotal = $scope.results.friendsTotal || data.friendsTotal || 0;
        $scope.results.othersTotal = $scope.results.othersTotal || data.othersTotal || 0;

        var hits = data.hits || [];
        if (hits.length) {
          $scope.keeps.push.apply($scope.keeps, hits);
        }

        if (!data.mayHaveMore) {
          $scope.scrollDisabled = true;
        }

        lastResult = data;
      });
    };

    $scope.getNextKeeps();
  }
]);
