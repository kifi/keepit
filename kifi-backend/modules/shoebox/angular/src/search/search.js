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
  '$http', '$scope', 'keepService', '$routeParams', '$location', '$window', 'routeService', '$log',
  function ($http, $scope, keepService, $routeParams, $location, $window, routeService, $log) {
    keepService.reset();

    if ($scope.search) {
      $scope.search.text = $routeParams.q;
    }

    var reportSearchAnalytics = function () {
      var url = routeService.searchedAnalytics;
      var lastSearchContext = keepService.lastSearchContext();
      if (lastSearchContext && lastSearchContext.query) {
        var origin = $location.$$protocol + '://' + $location.$$host;
        if ($location.$$port) {
          origin = origin + ':' + $location.$$port;
        }
        var data = {
          origin: origin,
          uuid: lastSearchContext.uuid,
          experimentId: lastSearchContext.experimentId,
          query: lastSearchContext.query,
          filter: lastSearchContext.filter,
          maxResults: lastSearchContext.maxResults,
          kifiExpanded: true,
          kifiResults: keepService.list.length,
          kifiTime: lastSearchContext.kifiTime,
          kifiShownTime: lastSearchContext.kifiShownTime,
          kifiResultsClicked: lastSearchContext.clicks,
          refinements: keepService.refinements,
          pageSession: lastSearchContext.pageSession,
          endedWith: lastSearchContext.endedWith
        };
        $http.post(url, data)['catch'](function (res) {
          $log.log('res: ', res);
        });
      } else {
        $log.log('no search context to log');
      }
    };

    $scope.$on('$destroy', function () {
      reportSearchAnalytics();
      $window.removeEventListener('beforeunload', reportSearchAnalytics);
    });

    $window.addEventListener('beforeunload', reportSearchAnalytics);

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
      return [keep, $event]; // log analytics for search click here
    };

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }
      reportSearchAnalytics();

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
