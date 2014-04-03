'use strict';

angular.module('kifi.search', [
  'util',
  'kifi.keepService'
])

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
  '$scope', 'keepService', '$routeParams', '$location', '$window',
  function ($scope, keepService, $routeParams, $location, $window) {
    keepService.reset();

    if ($scope.search) {
      $scope.search.text = $routeParams.q;
    }

    if (!$routeParams.q) {
      // No or blank query
      $location.path('/');
    }

    var query = $routeParams.q || '',
      filter = $routeParams.f || 'm',
      lastResult = null;

    $window.document.title = query === '' ? 'Kifi • Search' : 'Kifi • ' + query;

    $scope.keepService = keepService;
    $scope.keeps = keepService.list;

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

    $scope.toggleSelectAll = keepService.toggleSelectAll;
    $scope.isSelectedAll = keepService.isSelectedAll;

    $scope.isMultiChecked = function () {
      return keepService.getSelectedLength() > 0 && !keepService.isSelectedAll();
    };

    $scope.isCheckEnabled = function () {
      return $scope.keeps.length;
    };

    $scope.hasMore = function () {
      return !keepService.isEnd();
    };

    $scope.mouseoverCheckAll = false;

    $scope.onMouseoverCheckAll = function () {
      $scope.mouseoverCheckAll = true;
    };

    $scope.onMouseoutCheckAll = function () {
      $scope.mouseoverCheckAll = false;
    };

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Searching…';
      }

      var subtitle = keepService.getSubtitle($scope.mouseoverCheckAll);
      if (subtitle) {
        return subtitle;
      }

      var numShown = $scope.keeps.length;
      switch (numShown) {
      case 0:
        return 'Sorry, no results found for “' + query + '”';
      case 1:
        return '1 result found';
      default:
        return 'Top ' + numShown + ' results';
      }
    };

    $scope.scrollDistance = '100%';
    $scope.scrollDisabled = false;

    $scope.analyticsTrack = function (keep, $event) {
      console.log('logged!', keep, $event);
    }

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;
      keepService.find(query, filter, lastResult && lastResult.context).then(function (data) {
        $scope.loading = false;

        $scope.results.myTotal = $scope.results.myTotal || data.myTotal;
        $scope.results.friendsTotal = $scope.results.friendsTotal || data.friendsTotal;
        $scope.results.othersTotal = $scope.results.othersTotal || data.othersTotal;

        if (keepService.isEnd()) {
          $scope.scrollDisabled = true;
        }

        lastResult = data;
      });
    };

    $scope.getNextKeeps();
  }
]);
