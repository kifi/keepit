'use strict';

angular.module('kifi')

.factory('searchActionService', [
  '$analytics', '$location', '$log', '$q',
  'net', 'profileService', 'libraryService',
  function ($analytics, $location, $log, $q,
    net, profileService, libraryService) {
    //
    // Internal helper methods.
    //
    function createPageSession() {
      return Math.random().toString(16).slice(2);
    }

    function decompressUriHit(hit, users, libraries, userLoggedIn) {
      hit.user = hit.user === -1 ? profileService.me : users[hit.user];
      hit.library = libraries[hit.library];

      var decompressedKeepers = [];
      var decompressedLibraries = [];

      hit.keepers = hit.keepers || [];
      hit.libraries = hit.libraries || [];

      for (var i = 0; i < hit.libraries.length; i = i + 2) {
        var idxLib = hit.libraries[i];
        var idxUser = hit.libraries[i + 1];
        var lib = libraries[idxLib];

        if (idxUser !== -1) {
          decompressedLibraries.push([lib, users[idxUser]]);
        } else if (userLoggedIn && !libraryService.isLibraryIdMainOrSecret(lib.id)) {
          decompressedLibraries.push([lib, profileService.me]);
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

      // reconstructing old .summary format for consistency with library keeps and reco keeps
      if (!hit.summary) {
        var img = hit.image;
        hit.summary = {
          description: hit.description,
          imageUrl: img && img.url,
          imageWidth: img && img.width,
          imageHeight: img && img.height
        };
      }
      hit.summary.wordCount = hit.wordCount;
    }

    function reportSearchAnalytics(endedWith, numResults, numResultsWithLibraries) {
      if (lastSearchContext && lastSearchContext.query) {
        var origin = $location.$$protocol + '://' + $location.$$host;
        if ($location.$$port) {
          origin = origin + ':' + $location.$$port;
        }
        net.search.searched({
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
        })['catch'](function (res) {
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
      var searchActionPromise = net.search.search(params);
      var librarySummariesPromise = userLoggedIn ? libraryService.fetchLibraryInfos(false) : null;

      // ensuring library summaries have been loaded before the hits are decompressed
      return $q.all([librarySummariesPromise, searchActionPromise]).then(function (results) {
        var resData = results[1].data;
        var uris = resData.uris || {};
        var hits = uris.hits || [];
        _.forEach(hits, function (hit) {
          decompressUriHit(hit, uris.keepers, uris.libraries, userLoggedIn);
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
          title: keep.title,
          titleMatches: 0, //This broke with new search api (the information is no longer available). Needs to be investigated if we still need it.
          urlMatches: 0
        };
        net.search.resultClicked({
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
        })['catch'](function (res) {
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
