'use strict';

angular.module('kifi')

.controller('SearchCtrl', [
  '$scope', '$rootScope', '$location', '$q', '$state', '$stateParams', '$timeout', '$window', '$$rAF', '$http',
  'keepDecoratorService', 'searchActionService', 'libraryService', 'routeService', 'util', 'library',
  function ($scope, $rootScope, $location, $q, $state, $stateParams, $timeout, $window, $$rAF, $http,
            keepDecoratorService, searchActionService, libraryService, routeService, util, library) {
    //
    // Internal data.
    //
    var queryCount = 0;
    var query;
    var filter;
    var context;
    var renderTimeout;
    var smoothScrollStep;  // used to ensure that only one smooth scroll animation happens at a time


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
    $scope.edit = {
      enabled: false,
      actions: {
        keepToLibrary: true
      }
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
      queryCount++;
      query = $stateParams.q || '';
      filter = $stateParams.f || 'a';

      if (!query) {
        $state.go(library ? 'library.keeps' : 'home');
        return;
      }

      context = null;
      $timeout.cancel(renderTimeout);
      renderTimeout = null;

      $scope.hasMore = true;
      $scope.scrollDistance = '100%';
      $scope.loading = false;
      $scope.edit.enabled = false;

      document.title = (library && library.name ? library.name + ' • ' : '') + query.replace(/^tag:/, '') + ' • Kifi';

      searchActionService.reset();
      $scope.getNextKeeps(true);

      $timeout(function () {
        if (library) {
          var content = angular.element('.kf-lib-content');
          var header = angular.element('.kf-lih,.kf-loh');
          if (content.length && header.length) {
            smoothScroll(content[0].getBoundingClientRect().top - header[0].offsetHeight);
          }
        } else {
          $window.document.body.scrollTop = 0;
        }
      });
    }

    function smoothScroll(px) {
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
      var ms_1 = 1 / Math.max(400, Math.min(800, 100 * Math.log(Math.abs(px))));
      var step = smoothScrollStep = function (t) {  // jshint ignore:line
        if (step !== smoothScrollStep) {
          return;
        }
        if (!t0) {
          t0 = t;
        }
        var pxTarget = Math.round(px * easeInOutQuart(Math.min(1, (t - t0) * ms_1)));
        win.scrollBy(0, pxTarget - pxScrolled);
        win.dispatchEvent(newScrollEvent());
        pxScrolled = pxTarget;
        if (Math.abs(pxScrolled) < Math.abs(px)) {
          $$rAF(step);
        } else {
          smoothScrollStep = null;
        }
      };
      $$rAF(step);
    }

    function easeInOutQuart(t) {
      return t < 0.5 ? 8 * t * t * t * t : 1 - 8 * (--t) * t * t * t;
    }

    //
    // Scope methods.
    //

    $scope.getNextKeeps = function (resetExistingResults) {
      if ($scope.loading || query === '') {
        return;
      }

      $scope.loading = true;
      var firstBatch = !context;
      searchActionService.find(query, filter, library, context, $rootScope.userLoggedIn).then(function (queryNumber, result) {
        if (queryNumber !== queryCount) {  // results are for an old query
          return;
        }

        context = result.uris.context;

        if (resetExistingResults) {
          $scope.resultKeeps.length = 0;
          $scope.resultTotals.myTotal = 0;
          $scope.resultTotals.friendsTotal = 0;
          $scope.resultTotals.othersTotal = 0;
        }
        $scope.resultTotals.myTotal = $scope.resultTotals.myTotal || result.uris.myTotal;
        $scope.resultTotals.friendsTotal = $scope.resultTotals.friendsTotal || result.uris.friendsTotal;
        $scope.resultTotals.othersTotal = $scope.resultTotals.othersTotal || result.uris.othersTotal;

        var hits = result.uris.hits;
        if (hits.length) {
          $scope.hasMore = !!result.uris.mayHaveMore;
          renderTimeout = $timeout(angular.bind(null, renderNextKeep, hits.slice()));
        } else {
          $scope.hasMore = false;
          onDoneWithBatchOfKeeps();
        }

        if (firstBatch) {
          var libs = $scope.matchingLibraries = result.libraries.hits.map(unpackLibrary);
          if (libs.length) {
            loadImagesAndAugment(libs);
          }
        }
      }.bind(null, queryCount));
    };

    function renderNextKeep(keeps) {
      var keep = new keepDecoratorService.Keep(keeps.shift());
      if (keep.id) {
        keep.buildKeep(keep);
      }
      // TODO remove after we get rid of the deprecated code and update new code to use 'tags' instead of 'hashtags'
      keep.hashtags = keep.tags;
      $scope.resultKeeps.push(keep);
      if (keeps.length) {
        renderTimeout = $timeout(angular.bind(null, renderNextKeep, keeps));
      } else {
        onDoneWithBatchOfKeeps();
      }
    }

    function onDoneWithBatchOfKeeps() {
      $scope.loading = false;
    }

    function unpackLibrary(library) {
      return {
        id: library.id,
        owner: library.owner,
        numFollowers: library.memberCount - 1,
        numKeeps: library.keepCount,
        name: library.name,
        description: library.description,
        color: library.color,
        image: library.image,
        path: library.path,
        reason: 'topic'
      };
    }

    function loadImagesAndAugment(libs) {
      $http.get(routeService.getLibraryCoverImages(_.pluck(libs, 'id'), 400, 400)).then(function (res) {
        var imagesById = res.data;
        _.each(libs, function (lib) {
          lib.image = imagesById[lib.id];
        });
      });
    }

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
        library = null;
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
    function onUnload() {
      var resultsWithLibs = 0;
      $scope.resultKeeps.forEach( function (keep) {
        // does there exist a library that's not system_created?
        if (_.some(keep.libraries, function (lib) { return !libraryService.isLibraryIdMainOrSecret(lib.id); })) {
          resultsWithLibs++;
        }
      });
      searchActionService.reportSearchAnalyticsOnUnload($scope.resultKeeps.length, resultsWithLibs);
    }

    $window.addEventListener('beforeunload', onUnload);

    $scope.$on('$destroy', function () {
      onUnload();
      $window.removeEventListener('beforeunload', onUnload);
    });

    // used for bulk-edit Copy To Library in search, it updates the model to include the new library keep(s)
    var deregisterKeepAddedListener = $rootScope.$on('keepAdded', function (event, keeps, library) {
      keeps.forEach(function (keep) {
        var searchKeep = _.find($scope.resultKeeps, { url: keep.url });
        if (searchKeep && !_.find(searchKeep.keeps, { id: keep.id })) {
          searchKeep.keeps.push({
            id: keep.id,
            libraryId: library.id,
            visibility: library.visibility
          });
        }
      });
    });
    $scope.$on('$destroy', deregisterKeepAddedListener);

    init();
  }
]);
