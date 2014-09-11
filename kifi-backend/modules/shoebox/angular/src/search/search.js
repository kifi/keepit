'use strict';

angular.module('kifi')

.controller('SearchCtrl', [
  '$scope', '$location', '$routeParams', '$window', 'keepDecoratorService', 'searchActionService',
  function ($scope, $location, $routeParams, $window, keepDecoratorService, searchActionService) {
    //
    // Internal data.
    //
    var query = $routeParams.q || '';
    var filter = $routeParams.f || 'm';
    var lastResult = null;
    var selectedCount = 0;


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
    $scope.loading = false;


    //
    // Internal helper methods.
    //
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


    //
    // Scope methods.
    //
    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;
      searchActionService.find(query, filter, lastResult && lastResult.context).then (function (result) {
        var hits = result.hits;
        
        hits.forEach(function (hit) {
          var searchKeep = new keepDecoratorService.Keep(hit);
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

    $scope.getFilterUrl = function (type) {
      if ($scope.isEnabled(type)) {
        var count = getFilterCount(type);
        if (count) {
          return '/find?q=' + query + '&f=' + type;
        }
      }
      return '';
    };

    $scope.isFilterSelected = function (type) {
      return filter === type;
    };

    $scope.isEnabled = function (type) {
      return !$scope.isFilterSelected(type) && !!getFilterCount(type);
    };

    $scope.allowEdit = function () {
      return !$scope.isFilterSelected('f') && !$scope.isFilterSelected('a');
    };

    $scope.analyticsTrack = function (keep, $event) {
      searchActionService.reportSearchClickAnalytics(keep, $scope.resultKeeps.indexOf(keep), $scope.resultKeeps.length);
      return [keep, $event]; // log analytics for search click here
    };
    
    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Searching…';
      }

      // If there are selected keeps, display the number of keeps
      // in the subtitle.
      if (selectedCount > 0) {
        switch (selectedCount) {
          case 0:
            return null;
          case 1:
            return selectedCount + ' Keep selected';
          default:
            return selectedCount + ' Keeps selected';
        }
      }

      // If there are no selected keep, the display the number of
      // search results in the subtitle.
      var numShown = $scope.resultKeeps.length;
      switch (numShown) {
        case 0:
          return 'Sorry, no results found for “' + query + '”';
        case 1:
          return '1 result found';
        default:
          return 'Top ' + numShown + ' results';
      }
    };

    $scope.updateSelectedCount = function (numSelected) {
      selectedCount = numSelected;
    };


    //
    // Watches and event listeners.
    //

    // Report search analytics on unload.
    var onUnload = function () {
      searchActionService.reportSearchAnalyticsOnUnload($scope.resultKeeps.length);
    };
    $window.addEventListener('beforeunload', onUnload);

    $scope.$on('$destroy', function () {
    onUnload();
      $window.removeEventListener('beforeunload', onUnload);
    });

    
    //
    // On SearchCtrl initialization.
    //
    if (!query) {
      // No query or blank query.
      $location.path('/');
    }

    $window.document.title = 'Kifi • ' + query;

    // Populate search bar input with current query and display the search bar.
    if ($scope.search) {
      $scope.search.text = query;
    }
    $scope.enableSearch();

    searchActionService.reset();
    $scope.getNextKeeps();
  }
]);
