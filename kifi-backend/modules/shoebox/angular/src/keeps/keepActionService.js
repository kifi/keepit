'use strict';

angular.module('kifi')

.factory('keepActionService', [
  '$analytics', '$http', '$location', 'libraryService', 'routeService',
  function ($analytics, $http, $location, libraryService, routeService) {

    function sanitizeUrl(url) {
      var regex = /^[a-zA-Z]+:\/\//;
      if (!regex.test(url)) {
        return 'http://' + url;
      } else {
        return url;
      }
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

      var url = routeService.addKeepsToLibrary(libraryId);
      return $http.post(url, data, {}).then(function (res) {
        libraryService.rememberRecentId(libraryId);

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

      var url = routeService.copyKeepsToLibrary();
      return $http.post(url, data, {}).then(function (res) {
        libraryService.rememberRecentId(libraryId);

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

      var url = routeService.moveKeepsToLibrary();
      return $http.post(url, data, {}).then(function (res) {
        libraryService.rememberRecentId(libraryId);

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
      libraryService.expireKeepsInLibraries();
      var url = routeService.removeKeepFromLibrary(libraryId, keepId);
      return $http.delete(url);  // jshint ignore:line
    }

    function unkeepManyFromLibrary(libraryId, keeps) {
      libraryService.expireKeepsInLibraries();
      var url = routeService.removeManyKeepsFromLibrary(libraryId);
      var data = {
        'ids': _.pluck(keeps, 'id')
      };
      return $http.post(url, data);
    }

    var api = {
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
