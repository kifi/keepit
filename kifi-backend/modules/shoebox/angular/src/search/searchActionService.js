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

    function processHit(hit) {
      _.extend(hit, hit.bookmark); //still need this??

      hit.isPrivate = hit.secret || false;
      hit.isProtected = !hit.isMyBookmark; // will not be hidden if user keeps then unkeeps
    }

    function copy(obj) {
      return JSON.parse(JSON.stringify(obj));
    }

    function decompressHit(hit, users, libraries, userLoggedIn) {
      var decompressedKeepers = [];
      var decompressedLibraries = [];
      var myLibraries = [];  // myLibraries doesn't seem to be used anywhere.
      var libUsers = {};

      hit.isMyBookmark = false;
      hit.keepers = hit.keepers || [];
      hit.libraries = hit.libraries || [];

      for (var i = 0; i < hit.libraries.length; i = i + 2) {
        var idxLib = hit.libraries[i];
        var idxUser = hit.libraries[i + 1];
        var lib = libraries[idxLib];
        var user;

        if (idxUser !== -1) {
          user = users[idxUser];
          lib.keeperName = user.firstName + ' ' + user.lastName;
          lib.owner = user;
          decompressedLibraries.push(lib);
          libUsers[idxUser] = true;
        } else if (userLoggedIn) {
          user = profileService.me;
          lib.keeperName = user.firstName + ' ' + user.lastName;
          lib.owner = user;

          if (!libraryService.isLibraryIdMainOrSecret(lib.id)) {
            decompressedLibraries.push(lib);
          }

          myLibraries.push(lib);
        }
      }

      if (userLoggedIn) {
        hit.keepers.forEach(function (keeperIdx) {
          if (keeperIdx === -1) {
            hit.isMyBookmark = true;
          } else {
            if (!libUsers[keeperIdx]){
              decompressedKeepers.push(users[keeperIdx]);
            } else {
              var user = copy(users[keeperIdx]);
              user.hidden = true;
              decompressedKeepers.push(user);
            }
          }
        });
      }

      hit.keepers = decompressedKeepers;
      hit.libraries = decompressedLibraries;
      hit.myLibraries = myLibraries;
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

        _.forEach(hits, processHit);

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
        var hitContext = {
          isMyBookmark: keep.isMyBookmark,
          isPrivate: keep.isPrivate,
          count: numResults,
          keepers: keep.keepers.map(function (elem) {
            return elem.id;
          }),
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


    var api = {
      find: find,
      reset: reset,
      reportSearchAnalyticsOnUnload: reportSearchAnalyticsOnUnload,
      reportSearchAnalyticsOnRefine: reportSearchAnalyticsOnRefine,
      reportSearchClickAnalytics: reportSearchClickAnalytics
    };

    return api;
  }
]);
