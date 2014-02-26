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
  function ($scope, keepService) {
    console.log($routeParams);

    $scope.results = {
      numShown: 0,
      myTotal: 300,
      friendsTotal: 0,
      othersTotal: 12342
    };

    $scope.filter = {
      type: 'm'
    };

    $scope.isFilterSelected = function (type) {
      return $scope.filter.type === type;
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
          return '/find?q=' + ($scope.results.query || '') + '&f=' + type + '&maxHits=30';
        }
      }
      return '';
    };

    $scope.getSubtitle = function () {
      var numShown = $scope.results.numShown;

      if ($scope.isSearching) {
        return 'Searching...';
      }

      switch (numShown) {
      case 0:
        return 'Sorry, no results found for &#x201c;' + ($scope.results.query || '') + '&#x202c;';
      case 1:
        return '1 result found';
      default:
        return 'Top ' + numShown + ' results';
      }

    };
  }
]);
