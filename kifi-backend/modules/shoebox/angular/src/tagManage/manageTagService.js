'use strict';

angular.module('kifi')

.factory('manageTagService', [
  '$http', 'routeService', 'Clutch', '$q',
  function ($http, routeService, Clutch, $q) {
    var pageSize = 100;
    var searchLimit = 30;

    var decorate = function (tags) {
      return _.map(tags || [], function (t) {
        var tagPath;
        var n = t.name;
        if (n.indexOf(' ') !== -1) {
          tagPath = '"' + n + '"';
        } else {
          tagPath = n;
        }
        t.href = '/find?q=tag:' + tagPath;
        return t;
      });
    };

    var manageTagRemoteService = new Clutch(function (sort, offset) {
      return $http.get(routeService.pageTags + '?sort=' + sort + '&offset=' + offset + '&pageSize=' + pageSize
        ).then(function (res) {
          return decorate(res.data.tags);
        }
      );
    });

    var tagSearchService = new Clutch(function (query) {
      if (!query || !query.trim()) {
        return $q.when([]);
      }
      return $http.get(routeService.searchTags(query, searchLimit)).then(function (res) {
        var results = res.data && res.data.results || [];
        var tags = _.map(results, function (tag) {
          return { name : tag.tag, keeps: tag.keepCount };
        });
        return decorate(tags);
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
        return tagSearchService.get(query);
      }
    };

    return api;
  }
]);
