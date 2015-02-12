'use strict';

angular.module('kifi')

.controller('SearchCtrl', [
  '$scope', '$rootScope', '$location', '$q', '$state', '$stateParams', '$timeout', '$window', '$$rAF',
  'keepDecoratorService', 'searchActionService', 'libraryService', 'util', 'library',
  function ($scope, $rootScope, $location, $q, $state, $stateParams, $timeout, $window, $$rAF,
            keepDecoratorService, searchActionService, libraryService, util, library) {
    //
    // Internal data.
    //
    var query;
    var filter;
    var lastResult = null;
    var selectedCount = 0;
    var numResults = 0;


    //
    // Scope data.
    //
    $scope.isLibrarySearch = !!library;
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

    function setTitle() {
      function getNormalizedQuery() {
        if (util.startsWith(query, 'tag:')) {
          return query.slice(4);
        }
        return query;
      }

      var libraryPart = library && library.name ? library.name + ' • ' : '';
      $window.document.title = libraryPart + getNormalizedQuery() + ' • Kifi';
    }

    function init() {
      query = $stateParams.q || '';
      filter = $stateParams.f || 'a';

      if (!query) {
        $state.go(library ? 'library.keeps' : 'home');
        return;
      }

      lastResult = null;
      selectedCount = 0;

      $scope.hasMore = true;
      $scope.scrollDistance = '100%';
      $scope.loading = false;

      setTitle();

      searchActionService.reset();
      $scope.getNextKeeps(true);

      $timeout(function () {
        if (library) {
          var pxToScroll = angular.element('.kf-lib-cols')[0].getBoundingClientRect().top - angular.element('.kf-lih,.kf-loh')[0].offsetHeight;
          if (pxToScroll > 0) {
            scrollDown(pxToScroll);
          }
        } else {
          $window.document.body.scrollTop = 0;
        }
      });
    }

    function scrollDown(px) {
      var doc = $window.document;
      var win = doc.defaultView;
      var newScrollEvent = typeof UIEvent === 'function' ?
        function () {
          return new win.UIEvent('scroll');
        } :
        function () {
          var e = doc.createEvent('UIEvent');
          e.initUIEvent('scroll', false, false, win, 0);
          return e;
        };

      var t0, pxScrolled = 0;
      var ms_1 = 1 / Math.max(400, Math.min(800, 100 * Math.log(px)));
      $$rAF(function step(t) {
        if (!t0) {
          t0 = t;
        }
        var pxTarget = Math.round(px * easeInOutQuart(Math.min(1, (t - t0) * ms_1)));
        win.scrollBy(0, pxTarget - pxScrolled);
        win.dispatchEvent(newScrollEvent());
        pxScrolled = pxTarget;
        if (pxScrolled < px) {
          $$rAF(step);
        }
      });
    }

    function easeInOutQuart(t) {
      return t < 0.5 ? 8 * t * t * t * t : 1 - 8 * (--t) * t * t * t;
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
      searchActionService.find(query, filter, library, lastResult && lastResult.context, $rootScope.userLoggedIn).then(function (q, result) {
        if (q !== query) {  // query was updated
          return;
        }

        $scope.hasMore = !!result.mayHaveMore;
        lastResult = result;

        if (resetExistingResults) {
          $scope.resultKeeps.length = 0;
          $scope.resultTotals.myTotal = 0;
          $scope.resultTotals.friendsTotal = 0;
          $scope.resultTotals.othersTotal = 0;
          numResults = 0;
        }
        $scope.resultTotals.myTotal = $scope.resultTotals.myTotal || result.myTotal;
        $scope.resultTotals.friendsTotal = $scope.resultTotals.friendsTotal || result.friendsTotal;
        $scope.resultTotals.othersTotal = $scope.resultTotals.othersTotal || result.othersTotal;
        numResults = result.hits.length;

        var hits = result.hits;
        var hitIndex = 0;

        function processHit() {
          // If query has changed or if we've finished processing all the hits, exit.
          if ((q !== query) ||  (hitIndex >= hits.length)) {
            return;
          }

          var hit = hits[hitIndex];
          var searchKeep = new keepDecoratorService.Keep(hit);
          if (!!searchKeep.id) {
            searchKeep.buildKeep(searchKeep);
          }

          // TODO remove after we get rid of the deprecated code and update new code to use 'tags' instead of 'hashtags'
          searchKeep.hashtags = searchKeep.tags;
          $scope.resultKeeps.push(searchKeep);

          hitIndex++;
          $timeout(processHit);
        }

        // Process one hit per event loop turn to allow other events to come through.
        $timeout(function () {
          processHit();
          $scope.loading = false;
        });
      }.bind(null, query));
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
      switch (numResults) {
        case 0:
          return 'Sorry, no results found for “' + query + '”';
        case 1:
          return '1 result found';
        default:
          return 'Top ' + numResults + ' results';
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

    $scope.onClickSearchFilter = function (newSearchFilter) {
      $location.search({f: newSearchFilter});
    };


    //
    // Watches and event listeners.
    //
    function newSearch() {
      _.assign($stateParams, _.pick($location.search(), 'q', 'f'));
      init();
    }

    $scope.$on('$destroy', $rootScope.$on('searchTextUpdated', function (e, newSearchText, libraryUrl) {
      if ($location.path() === '/find') {
        $location.search('q', newSearchText).replace(); // this keeps any existing URL params
      } else if (libraryUrl) {
        if (newSearchText) {
          if ($stateParams.q) {
            $location.search('q', newSearchText).replace();
          } else {
            $location.url(libraryUrl + '/find?q=' + newSearchText + '&f=a');
          }
        } else {
          $location.url(libraryUrl);
        }
      } else if (newSearchText) {
        $location.url('/find?q=' + newSearchText);
      }

      newSearch();
    }));

    $scope.$on('$locationChangeSuccess', function (event, newState, oldState) {
      var newPath = newState.slice(0, newState.indexOf('?'));
      var oldPath = oldState.slice(0, oldState.indexOf('?'));

      // If we are going from one search to another, update the search.
      if (newPath === oldPath) {
        newSearch();
        $rootScope.$emit('newQueryFromLocation', $stateParams.q);
      }
    });

    // Report search analytics on unload.
    var onUnload = function () {
      var resultsWithLibs = 0;
      $scope.resultKeeps.forEach( function (keep) {
        if (_.some(keep.libraries, function (libId) { return !libraryService.isSystemLibraryById(libId); })) {
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
