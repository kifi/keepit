'use strict';

angular.module('kifi')

.factory('keepActionService', [
  '$analytics', '$http', '$location', '$log', '$q', 'env', 'libraryService', 'routeService', 'Clutch',
  function ($analytics, $http, $location, $log, $q, env, libraryService, routeService, Clutch) {
    var limit = 10;
    var smallLimit = 4;

    var keepList = new Clutch(function (url, config) {
      $log.log('keepActionService.getList()', config && config.params);

      return $http.get(url, config).then(function (res) {
        return res && res.data;
      });
    });

    function sanitizeUrl(url) {
      var regex = /^[a-zA-Z]+:\/\//;
      if (!regex.test(url)) {
        return 'http://' + url;
      } else {
        return url;
      }
    }

    function getKeeps(lastKeepId, params) {
      var url = env.xhrBase + '/keeps/all';

      params = params || {};
      params.withPageInfo = true;

      // Pass the id of the last keep received to the server so the server
      // knows which keeps to send next.
      params.before = lastKeepId;

      // Request a smaller number of keeps in the first request.
      params.count = lastKeepId ? params.count || limit : smallLimit;

      var config = {
        params: params
      };

      return keepList.get(url, config).then(function (data) {
        var result = {};

        result.keeps = data.keeps;
        result.mayHaveMore = data.keeps.length === params.count;

        return result;
      });
    }

    function getKeepsByTagId(tagId, lastKeepId, params) {
      params = params || {};
      params.collection = tagId;
      return getKeeps(lastKeepId, params);
    }

    function getKeepsByHelpRank(helprank, lastKeepId, params) {
      params = params || {};
      params.helprank = helprank;
      return getKeeps(lastKeepId, params);
    }

    function getSingleKeep(keepId) {
      var url = routeService.getKeep(keepId);
      var config = {
        params: { withFullInfo: true }
      };

      return $http.get(url, config).then(function (result) {
        return result && result.data;
      });
    }

    function keepToLibrary(keepInfos, libraryId) {
      $analytics.eventTrack('user_clicked_page', {
        // TODO(yiping): should we have a different action
        // for keeping to library?
        'action': 'keep',
        'path': $location.path()
      });

      var data = {
        keeps: keepInfos.map(function(keep) {
          var keepData = { url: sanitizeUrl(keep.url) };
          if (keep.title) { keepData.title = keep.title; }
          return keepData;
        })
      };

      $log.log('keepActionService.keepToLibrary()', data);

      var url = routeService.addKeepsToLibrary(libraryId);
      return $http.post(url, data, {}).then(function (res) {
        libraryService.addRecentLibrary(libraryId);

        _.uniq(res.data.keeps, function (keep) {
          return keep.url;
        });

        return res.data;
      });
    }

    function copyToLibrary(keepIds, libraryId) {
      $analytics.eventTrack('user_clicked_page', {
        // TODO(yiping): should we have a different action
        // for keeping to library?
        'action': 'keep',
        'path': $location.path()
      });

      var data = {
        to: libraryId,
        keeps: keepIds
      };

      $log.log('keepActionService.copyToLibrary()', data);

      var url = routeService.copyKeepsToLibrary();
      return $http.post(url, data, {}).then(function (res) {
        libraryService.addRecentLibrary(libraryId);

        _.uniq(res.data.keeps, function (keep) {
          return keep.url;
        });

        return res.data;
      });
    }

    function moveToLibrary(keepIds, libraryId) {
      $analytics.eventTrack('user_clicked_page', {
        // TODO(yiping): should we have a different action
        // for keeping to library?
        'action': 'keep',
        'path': $location.path()
      });

      var data = {
        to: libraryId,
        keeps: keepIds
      };

      $log.log('keepActionService.moveToLibrary()', data);

      var url = routeService.moveKeepsToLibrary();
      return $http.post(url, data, {}).then(function (res) {
        libraryService.addRecentLibrary(libraryId);

        _.uniq(res.data.keeps, function (keep) {
          return keep.url;
        });

        return res.data;
      });
    }

    // When a url is added as a keep, the returned keep does not have the full
    // keep information we need to display it. This function fetches that
    // information.
    function fetchFullKeepInfo(keep) {
      var url = routeService.getKeep(keep.id);
      var config = {
        params: { withFullInfo: true }
      };

      return $http.get(url, config).then(function (result) {
        return _.assign(keep, result.data);
      });
    }

    function unkeepFromLibrary(libraryId, keepId) {
      var url = routeService.removeKeepFromLibrary(libraryId, keepId);
      return $http.delete(url);  // jshint ignore:line
    }

    function unkeepManyFromLibrary(libraryId, keeps) {
      var url = routeService.removeManyKeepsFromLibrary(libraryId);
      var data = {
        'ids': _.pluck(keeps, 'id')
      };
      return $http.post(url, data);
    }

    var api = {
      getKeeps: getKeeps,
      getKeepsByTagId: getKeepsByTagId,
      getKeepsByHelpRank: getKeepsByHelpRank,
      getSingleKeep: getSingleKeep,
      keepToLibrary: keepToLibrary,
      copyToLibrary: copyToLibrary,
      moveToLibrary: moveToLibrary,
      fetchFullKeepInfo: fetchFullKeepInfo,
      unkeepFromLibrary: unkeepFromLibrary,
      unkeepManyFromLibrary: unkeepManyFromLibrary
    };

    return api;
  }
]);
