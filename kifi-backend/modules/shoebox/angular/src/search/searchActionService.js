'use strict';

angular.module('kifi')

.factory('searchActionService', [
  '$analytics', '$http', '$location', '$log', '$q', 'routeService',

  function ($analytics, $http, $location, $log, $q, routeService) {

    // Internal helper methods.
    function processHit(hit) {
      _.extend(hit, hit.bookmark);

      hit.keepers = hit.users;
      hit.others = hit.count - hit.users.length - (hit.isMyBookmark && !hit.isPrivate ? 1 : 0);
      hit.summary = hit.uriSummary;
      hit.isProtected = !hit.isMyBookmark; // will not be hidden if user keeps then unkeeps
    }

    // Exposed API methods.
    function find(query, filter, context) {
      var url = routeService.search,
        reqData = {
          params: {
            q: query || void 0,
            f: filter || 'm',
            maxHits: 30,
            context: context || void 0,
            withUriSummary: true
          }
        };

      $log.log('searchActionService.find() req', reqData);

      return $http.get(url, reqData).then(function (res) {
        var resData = res.data;
        $log.log('searchActionService.find() res', resData);

        var hits = resData.hits || [];
        _.forEach(hits, processHit);

        $analytics.eventTrack('user_clicked_page', {
          'action': 'searchKifi',
          'hits': hits.size,
          'mayHaveMore': resData.mayHaveMore,
          'path': $location.path()
        });
        
        // appendKeeps(hits);

        // refinements++;
        // lastSearchContext = {
        //   origin: $location.origin,
        //   uuid: res.data.uuid,
        //   experimentId: res.data.experimentId,
        //   query: reqData.params.q,
        //   filter: reqData.params.f,
        //   maxResults: reqData.params.maxHits,
        //   kifiTime: null,
        //   kifiShownTime: null,
        //   kifiResultsClicked: null,
        //   refinements: refinements,
        //   pageSession: pageSession
        // };

        return resData;
      });
    }

    var api = {
      find: find
    };

    return api;
  }
]);