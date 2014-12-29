'use strict';

angular.module('kifi')

.controller('SearchCtrl', [
  '$scope', '$rootScope', '$location', '$q', '$state', '$timeout', '$window', 'keepDecoratorService', 'searchActionService', 'libraryService',
  function ($scope, $rootScope, $location, $q, $state, $timeout, $window, keepDecoratorService, searchActionService, libraryService) {
    //
    // Internal data.
    //
    var query;
    var filter;
    var userName;
    var librarySlug;
    var library;
    var lastResult = null;
    var selectedCount = 0;
    var authToken = $location.search().authToken || '';


    //
    // Scope data.
    //
    $scope.resultKeeps = [];
    $scope.resultTotals = {
      myTotal: 0,
      friendsTotal: 0,
      othersTotal: 0
    };


    //
    // Internal methods.
    //
    function getFilterCount(type) {
      switch (type) {
      case 'm':
        return $scope.resultTotals.myTotal;
      case 'f':
        return $scope.resultTotals.friendsTotal;
      case 'a':
        return $scope.resultTotals.myTotal + $scope.resultTotals.friendsTotal + $scope.resultTotals.othersTotal;
      }
    }

    function init() {
      query = $state.params.q || '';
      filter = $state.params.f || 'a';
      userName = $state.params.username || '';
      librarySlug = $state.params.librarySlug || '';

      var libraryIdPromise = null;

      if (userName && librarySlug) {
        libraryIdPromise = libraryService.getLibraryByUserSlug(userName, librarySlug, authToken, false).then(function (library) {
          $rootScope.$emit('libraryUrl', library);
          return library.id;
        });
      } else {
        libraryIdPromise = $q.when('');
        $rootScope.$emit('libraryUrl', {});
      }

      libraryIdPromise.then(function (libraryId) {
        library = libraryId;

        if (!query) { // No query or blank query.
          $location.path('/');
        }
        lastResult = null;
        selectedCount = 0;

        $scope.hasMore = true;
        $scope.scrollDistance = '100%';
        $scope.loading = false;

        $window.document.title = 'Kifi • ' + query;

        searchActionService.reset();
        $scope.getNextKeeps(true);
      })['catch'](function (resp) {
        if (resp.status && resp.status === 403) {
          // TODO(yiping): how should we handle this case?
        }
      });  //jshint ignore:line

      $timeout(function () {
        $window.document.body.scrollTop = 0;
      });
    }


    //
    // Scope methods.
    //

    /*
     * returns an array of the user's `keep` objects fetched from the keep
     * cards that are selected. Each keep card (hit) is basically a URL. Each
     * hit has a `keeps` array that contains information about all keeps the
     * user has related to the URL.
     *
     * example input:
     *   [ {url: 'foo.com', keeps: [{id: 1}, {id: 2}]},
     *     {url: 'bar.com', keeps: [{id: 3}]} ]
     * example output:
     *   [ {url: 'foo.com', id: 1},
     *     {url: 'foo.com', id: 2},
     *     {url: 'bar.com', id: 3} ]
     */
    $scope.selectedKeepsFilter = function (hits) {
      return _.flatten(_.map(hits, function (hit) {
        return _.map(hit.keeps, function (keep) {
          var ret = { 'url': hit.url };
          return _.merge(ret, keep);
        });
      }));
    };

    $scope.getNextKeeps = function (resetExistingResults) {
      if ($scope.loading || query === '') {
        return;
      }

      $scope.loading = true;
      var searchedQuery = query;

      searchActionService.find(query, filter, library, lastResult && lastResult.context, $rootScope.userLoggedIn).then(function (result) {
        if (searchedQuery !== query) { // query was updated
          return;
        }
        if (resetExistingResults) {
          $scope.resultKeeps.length = 0;
          $scope.resultTotals.myTotal = 0;
          $scope.resultTotals.friendsTotal = 0;
          $scope.resultTotals.othersTotal = 0;
        }

        var hits = result.hits;

        hits.forEach(function (hit) {
          var searchKeep = new keepDecoratorService.Keep(hit);
          if (!!searchKeep.id) {
            searchKeep.buildKeep(searchKeep);
          }
          // TODO remove after we get rid of the deprecated code and update new code to use 'tags' instead of 'hashtags'
          searchKeep.hashtags = searchKeep.tags;
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
          var userNameSlug = (userName && librarySlug) ? '/' + userName + '/' + librarySlug : '';
          return userNameSlug + '/find?q=' + query + '&f=' + type;
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
        return (selectedCount === 1) ? '1 Keep selected' : selectedCount + ' Keeps selected';
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

    $scope.editOptions = {
      draggable: false,
      actions: {
        keepToLibrary: true
      }
    };


    //
    // Watches and event listeners.
    //
    var newSearch = _.debounce(function () {
        // Use $state.params instead of $stateParams because changes to $stateParams
        // does not propagate to HeaderCtrl when it is injected there.
        // See: http://stackoverflow.com/questions/23081397/ui-router-stateparams-vs-state-params
        _.assign($state.params, $location.search());
        init();
      },
      250,
      { 'leading': true }
    );
    $scope.$on('$locationChangeSuccess', newSearch);


    // Report search analytics on unload.
    var onUnload = function () {
      var resultsWithLibs = 0;
      $scope.resultKeeps.forEach( function (keep) {
        if (_.some(keep.libraries, function (lib) { return !libraryService.isSystemLibrary(lib); })) {
          resultsWithLibs++;
        }
      });
      searchActionService.reportSearchAnalyticsOnUnload($scope.resultKeeps.length, resultsWithLibs);
    };
    $window.addEventListener('beforeunload', onUnload);

    $scope.$on('$destroy', function () {
    onUnload();
      $window.removeEventListener('beforeunload', onUnload);
    });

    // used for bulk-edit Copy To Library in search, it updates the model to include the new library keep(s)
    var deregisterKeepAddedListener = $rootScope.$on('keepAdded', function (event, slug, keeps, library) {
      keeps.forEach(function (keep) {
        var searchKeep = _.find($scope.resultKeeps, { url: keep.url });
        if (searchKeep && !_.find(searchKeep.keeps, { id: keep.id })) {
          searchKeep.keeps.push({
            id: keep.id,
            isMine: true,
            libraryId: library.id,
            mine: true,
            visibility: library.visibility
          });
        }
      });
    });
    $scope.$on('$destroy', deregisterKeepAddedListener);


    init();
  }
]);
