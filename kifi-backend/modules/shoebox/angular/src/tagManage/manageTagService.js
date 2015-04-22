'use strict';

angular.module('kifi')

.factory('manageTagService', [
  '$http', 'routeService', 'Clutch', '$q',
  function ($http, routeService, Clutch, $q) {

    var pageClutch = new Clutch(function (sort, offset) {
      return $http.get(routeService.pageTags(sort, offset, 100)).then(function (res) {
        return res.data.tags;
      });
    });

    var searchClutch = new Clutch(function (query) {
      if (!query || !query.trim()) {
        return $q.when([]);
      }
      return $http.get(routeService.searchTags(query, 30)).then(function (res) {
        return _.map(res.data.results, function (r) {
          return {name: r.tag, keeps: r.keepCount};
        });
      });
    });

    return {
      reset: function () {
        pageClutch.expireAll();
      },
      getMore: function (sort, offset) {
        return pageClutch.get(sort, offset);
      },
      search: function (query) {
        return searchClutch.get(query);
      }
    };

  }
]);
