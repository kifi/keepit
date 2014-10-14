'use strict';

angular.module('kifi')

.factory('manageTagService', [
  '$http', 'routeService', 'Clutch', '$q',
  function ($http, routeService, Clutch, $q) {
    var pageSize = 100;
    var searchLimit = 30;

    var manageTagRemoteService = new Clutch(function (sort, offset) {
      return $http.get(routeService.pageTags + '?sort=' + sort + '&offset=' + offset + '&pageSize=' + pageSize
        ).then(function (res) {
          return res.data.tags;
        }
      );
    });

    var tagSearchService = new Clutch(function (query) {
      if (!query || !query.trim()) {
        return $q.when([]);
      }
      return $http.get(routeService.searchTags(query, searchLimit)).then(function (res) {
        return res.data;
      });
    });

    var api = {
      reset: function () {
        manageTagRemoteService.expireAll();
      },
      getMore: function (sort, offset) {
        return manageTagRemoteService.get(sort, offset);
      },
      search: function(query) {
        return tagSearchService.get(query).then(function (res) {
          var tags = _.map(res.results, function(tag) {
            return { name : tag.tag, keeps: tag.keepCount };
          });
          return tags;
        });
      }
    };

    return api;
  }
]);
