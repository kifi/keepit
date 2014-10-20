'use strict';

angular.module('kifi')

.factory('searchActionService', [
  '$analytics', '$http', '$location', '$log', '$q', 'routeService', 'profileService', 'friendService',

  function ($analytics, $http, $location, $log, $q, routeService, profileService, friendService) {
    //
    // Internal helper methods.
    //
    function createPageSession() {
      return Math.random().toString(16).slice(2);
    }

    function processHit(hit) {
      _.extend(hit, hit.bookmark); //still need this??

      hit.isPrivate = hit.secret || false;
      hit.others = hit.keepersTotal - hit.keepers.length;
      hit.isProtected = !hit.isMyBookmark; // will not be hidden if user keeps then unkeeps
    }

    function copy(obj) {
      return JSON.parse(JSON.stringify(obj));
    }

    function decompressHit(hit, users, libraries) {
      var librariesEnabled = profileService.me && profileService.me.experiments && profileService.me.experiments.indexOf('libraries') > -1;

      var decompressedKeepers = [];
      var decompressedLibraries = [];
      var libUsers = {};
      hit.isMyBookmark = false;
      hit.keepers = hit.keepers || [];
      hit.libraries = hit.libraries || [];



      for (var i=0; i<hit.libraries.length; i=i+2) {
        var idxLib = hit.libraries[i];
        var idxUser = hit.libraries[i+1];
        var lib = libraries[idxLib];
        if (idxUser !== -1) {
          var user = users[idxUser];
          decompressedLibraries.push({
            id: lib.id,
            name: lib.name,
            keeperPic: friendService.getPictureUrlForUser(user),
            path: lib.path
          });
          libUsers[idxUser] = true;
        }
      }

      hit.keepers.forEach( function (keeperIdx) {
        if (keeperIdx === -1) {
          hit.isMyBookmark = true;
        } else {
          if (!libUsers[keeperIdx]){
            decompressedKeepers.push(users[keeperIdx]);
          } else {
            var user = copy(users[keeperIdx]);
            user.hidden = true && librariesEnabled;
            decompressedKeepers.push(user);
          }
        }
      });

      hit.keepers = decompressedKeepers;
      hit.libraries = librariesEnabled ? decompressedLibraries : [];
    }

    function reportSearchAnalytics(endedWith, numResults) {
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
    function find(query, filter, context) {
      var url = routeService.search2,
        reqData = {
          params: {
            q: query || void 0,
            f: filter || 'm',
            maxHits: 10,
            context: context || void 0,
            withUriSummary: true
          }
        };

      $log.log('searchActionService.find() req', reqData);

      return $http.get(url, reqData).then(function (res) {
        var resData = res.data;
        $log.log('searchActionService.find() res', resData);

        var hits = resData.hits || [];
        _.forEach(hits, function (hit) {
          decompressHit(hit, resData.users, resData.libraries);
        });
        _.forEach(hits, processHit);

        $analytics.eventTrack('user_clicked_page', {
          'action': 'searchKifi',
          'hits': hits.size,
          'mayHaveMore': resData.mayHaveMore,
          'path': $location.path()
        });

        lastSearchContext = {
          origin: $location.origin,
          uuid: res.data.uuid,
          experimentId: res.data.experimentId,
          query: reqData.params.q,
          filter: reqData.params.f,
          maxResults: reqData.params.maxHits,
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

    function reportSearchAnalyticsOnUnload(numResults) {
      reportSearchAnalytics('unload', numResults);
    }

    function reportSearchAnalyticsOnRefine(numResults) {
      reportSearchAnalytics('refinement', numResults);
    }

    function reportSearchClickAnalytics(keep, resultPosition, numResults) {
      var url = routeService.searchResultClicked;
      if (lastSearchContext && lastSearchContext.query) {
        var origin = $location.$$protocol + '://' + $location.$$host;
        if ($location.$$port) {
          origin = origin + ':' + $location.$$port;
        }
        var matches = keep.bookmark.matches || (keep.bookmark.matches = {});
        var hitContext = {
          isMyBookmark: keep.isMyBookmark,
          isPrivate: keep.isPrivate,
          count: numResults,
          keepers: keep.keepers.map(function (elem) {
            return elem.id;
          }),
          tags: keep.tags,
          title: keep.bookmark.title,
          titleMatches: (matches.title || []).length,
          urlMatches: (matches.url || []).length
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
