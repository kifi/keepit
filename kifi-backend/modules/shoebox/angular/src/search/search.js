'use strict';

angular.module('kifi')

.controller('SearchCtrl', [
  '$http', '$scope', 'keepService', '$routeParams', '$location', '$window', 'routeService', '$log', 'searchActionService', 'cardService',
function ($http, $scope, keepService, $routeParams, $location, $window, routeService, $log, searchActionService, cardService) {
    //
    // Internal data.
    //
    var lastResult = null;

    //
    // Scope data.
    //
    $scope.resultKeeps = [];

    $scope.resultTotals = {
      myTotal: 0,
      friendsTotal: 0,
      othersTotal: 0
    };

    $scope.hasMore = true;
    $scope.scrollDistance = '100%';

    //
    // Internal helper methods.
    //

    //either "unload" or "refinement"
    var reportSearchAnalytics = function (endedWith) {
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
          endedWith: endedWith
        };
        $http.post(url, data)['catch'](function (res) {
          $log.log('res: ', res);
        });
      } else {
        $log.log('no search context to log');
      }
    };

    //
    // Scope methods.
    //
    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }
      // reportSearchAnalytics('refinement'); // Gotta move the stuff we need into SearchActionService first.

      $scope.loading = true;
      searchActionService.find(query, filter, lastResult && lastResult.context).then (function (result) {
        var hits = result.hits;
        
        hits.forEach(function (hit) {
          var searchKeep = new cardService.Card(hit);
          if (!!searchKeep.id) {
            searchKeep.buildKeep(searchKeep);
          }
          $scope.resultKeeps.push(searchKeep);
        });

        $scope.resultTotals.myTotal = $scope.resultTotals.myTotal || result.myTotal;
        $scope.resultTotals.friendsTotal = $scope.resultTotals.friendsTotal || result.friendsTotal;
        $scope.resultTotals.othersTotal = $scope.resultTotals.othersTotal || result.othersTotal;

        $scope.hasMore = !!result.mayHaveMore;

        lastResult = result;

        $scope.loading = false;
      });
    };

    // Unclear what/how $scope.search is affected.
    if ($scope.search) {
      $scope.search.text = $routeParams.q;
    }
    $scope.enableSearch();



    $scope.$on('$destroy', function () {
      searchActionService.reportSearchAnalyticsOnUnload();
      $window.removeEventListener('beforeunload', searchActionService.reportSearchAnalyticsOnUnload);
    });

    $window.addEventListener('beforeunload', searchActionService.reportSearchAnalyticsOnUnload);

    if (!$routeParams.q) {
      // No or blank query
      $location.path('/');
    }

    var query = $routeParams.q || '',
      filter = $routeParams.f || 'm';

    $window.document.title = query === '' ? 'Kifi • Search' : 'Kifi • ' + query;

    $scope.keepService = keepService;
    // $scope.keeps = keepService.list;

    

    $scope.isFilterSelected = function (type) {
      return filter === type;
    };

    function getFilterCount(type) {
      switch (type) {
      case 'm':
        return $scope.resultTotals.myTotal;
      case 'f':
        return $scope.resultTotals.friendsTotal;
      case 'a':
        return $scope.resultTotals.othersTotal;
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

    // Still need to migrate this.
    // $scope.getSubtitle = function () {
    //   if ($scope.loading) {
    //     return 'Searching…';
    //   }

    //   var subtitle = keepService.getSubtitle($scope.mouseoverCheckAll);
    //   if (subtitle) {
    //     return subtitle;
    //   }

    //   var numShown = $scope.keeps.length;
    //   switch (numShown) {
    //   case 0:
    //     return 'Sorry, no results found for “' + query + '”';
    //   case 1:
    //     return '1 result found';
    //   default:
    //     return 'Top ' + numShown + ' results';
    //   }
    // };

    

    $scope.analyticsTrack = function (keep, $event) {
      searchActionService.reportSearchClickAnalytics(keep, $scope.resultKeeps.indexOf(keep), $scope.resultKeeps.length);
      return [keep, $event]; // log analytics for search click here
    };



    function initKeepList() {
      $scope.scrollDisabled = false;
      lastResult = null;
      $scope.getNextKeeps();
    }

    $scope.$watch('keepService.seqReset()', function () {
      initKeepList();
    });

    $scope.allowEdit = function () {
      return !$scope.isFilterSelected('f') && !$scope.isFilterSelected('a');
    };

    // Sequence:
    // keepService.reset();
    // initKeepList();
    // getNextKeeps();
    // keepService.find();
    // --------------------
    //keepService.reset();

    //
    // On Angular load.
    //
    $scope.getNextKeeps();
  }
]);
