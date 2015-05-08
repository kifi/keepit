'use strict';

angular.module('kifi')

.factory('searchActionService', [
  '$analytics', '$http', '$location', '$log', '$q',
  'routeService', 'profileService', 'libraryService',
  function ($analytics, $http, $location, $log, $q,
    routeService, profileService, libraryService) {
    //
    // Internal helper methods.
    //
    function createPageSession() {
      return Math.random().toString(16).slice(2);
    }

    function decompressHit(hit, users, libraries, userLoggedIn) {
      var decompressedKeepers = [];
      var decompressedLibraries = [];

      hit.keepers = hit.keepers || [];
      hit.libraries = hit.libraries || [];

      for (var i = 0; i < hit.libraries.length; i = i + 2) {
        var idxLib = hit.libraries[i];
        var idxUser = hit.libraries[i + 1];
        var lib = libraries[idxLib];
        var user;

        if (idxUser !== -1) {
          user = users[idxUser];
          lib.owner = user;
          decompressedLibraries.push(lib);
        } else if (userLoggedIn) {
          user = profileService.me;
          lib.owner = user;

          if (!libraryService.isLibraryIdMainOrSecret(lib.id)) {
            decompressedLibraries.push(lib);
          }
        }
      }

      if (userLoggedIn) {
        hit.keepers.forEach(function (keeperIdx) {
          if (keeperIdx >= 0) {
            decompressedKeepers.push(users[keeperIdx]);
          }
        });
      }

      hit.keepers = decompressedKeepers;
      hit.libraries = decompressedLibraries;
    }

    function reportSearchAnalytics(endedWith, numResults, numResultsWithLibraries) {
      var url = routeService.searchedAnalytics;
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
          kifiResults: numResults,
          kifiResultsWithLibraries: numResultsWithLibraries,
          kifiTime: lastSearchContext.kifiTime,
          kifiShownTime: lastSearchContext.kifiShownTime,
          kifiResultsClicked: lastSearchContext.clicks,
          refinements: refinements,
          pageSession: lastSearchContext.pageSession,
          endedWith: endedWith
        };
        $http.post(url, data)['catch'](function (res) {
          $log.log('res: ', res);
        });
      } else {
        $log.log('no search context to log');
      }
    }


    //
    // Internal data.
    //
    var lastSearchContext = null;
    var refinements = -1;
    var pageSession = createPageSession();

    //
    // Exposed API methods.
    //
    function find(query, filter, library, context, userLoggedIn) {
      var params = {
        q: query,
        f: filter || [],
        l: library && library.id || [],
        disablePrefixSearch: 1,
        maxUris: 5,
        maxLibraries: context ? [] : 6,
        uriContext: context || []
      };
      var searchActionPromise = $http.get(routeService.search(params));
      var librarySummariesPromise = userLoggedIn ? libraryService.fetchLibraryInfos(false) : null;

      // ensuring library summaries have been loaded before the hits are decompressed
      return $q.all([librarySummariesPromise, searchActionPromise]).then(function (results) {
        var resData = results[1].data;
        var uris = resData.uris || {};
        var hits = uris.hits || [];
        _.forEach(hits, function (hit) {
          decompressHit(hit, uris.keepers, uris.libraries, userLoggedIn);
        });

        $analytics.eventTrack('user_clicked_page', {
          'action': 'searchKifi',
          'hits': hits.length,
          'mayHaveMore': uris.mayHaveMore,
          'path': $location.path()
        });

        lastSearchContext = {
          origin: $location.origin,
          uuid: uris.uuid,
          experimentId: resData.experimentId,
          query: params.q,
          filter: params.f,
          maxResults: params.maxUris,
          kifiTime: null,
          kifiShownTime: null,
          kifiResultsClicked: null,
          refinements: ++refinements,
          pageSession: pageSession
        };

        return resData;
      });
    }

    function reset() {
      lastSearchContext = null;
      refinements = -1;
      pageSession = createPageSession();
    }

    function reportSearchAnalyticsOnUnload(numResults, numResultsWithLibraries) {
      reportSearchAnalytics('unload', numResults, numResultsWithLibraries);
    }

    function reportSearchAnalyticsOnRefine(numResults, numResultsWithLibraries) {
      reportSearchAnalytics('refinement', numResults, numResultsWithLibraries);
    }

    function reportSearchClickAnalytics(keep, resultPosition, numResults) {
      var url = routeService.searchResultClicked;
      if (lastSearchContext && lastSearchContext.query) {
        var origin = $location.$$protocol + '://' + $location.$$host;
        if ($location.$$port) {
          origin = origin + ':' + $location.$$port;
        }
        var isMyBookmark = _.any(keep.keeps, _.identity);
        var hitContext = {
          isMyBookmark: isMyBookmark,
          isPrivate: isMyBookmark && _.all(keep.keeps, {visibility: 'secret'}),
          count: numResults,
          keepers: _.map(keep.keepers, 'id'),
          libraries: keep.libraries.map(function (elem) {
            return [elem.id, elem.owner.id];
          }),
          tags: keep.tags,
          title: keep.summary.title,
          titleMatches: 0, //This broke with new search api (the information is no longer available). Needs to be investigated if we still need it.
          urlMatches: 0
        };
        var data = {
          origin: origin,
          uuid: lastSearchContext.uuid,
          experimentId: lastSearchContext.experimentId,
          query: lastSearchContext.query,
          filter: lastSearchContext.filter,
          maxResults: lastSearchContext.maxResults,
          kifiExpanded: true,
          kifiResults: numResults,
          kifiTime: lastSearchContext.kifiTime,
          kifiShownTime: lastSearchContext.kifiShownTime,
          kifiResultsClicked: lastSearchContext.clicks,
          refinements: refinements,
          pageSession: lastSearchContext.pageSession,
          resultPosition: resultPosition,
          resultUrl: keep.url,
          hit: hitContext
        };
        $http.post(url, data)['catch'](function (res) {
          $log.log('res: ', res);
        });
      } else {
        $log.log('no search context to log');
      }
    }


    return {
      find: find,
      reset: reset,
      reportSearchAnalyticsOnUnload: reportSearchAnalyticsOnUnload,
      reportSearchAnalyticsOnRefine: reportSearchAnalyticsOnRefine,
      reportSearchClickAnalytics: reportSearchClickAnalytics
    };
  }
]);
