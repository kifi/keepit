'use strict';

angular.module('kifi')

.factory('manageTagService', [
  '$http', 'routeService', 'Clutch', '$q',
  function ($http, routeService, Clutch, $q) {
    var list = [];
    var more = true;
    var offset = 0;
    var pageSize = 10;

    var manageTagRemoteService = new Clutch(function (sort) {
      return $http.get(routeService.pageTags + '?sort=' + sort + '&offset=' + offset + '&pageSize=' + pageSize
        ).then(function (res) {
        if (res.data.tags.length === 0) {
          more = false;
        } else {
          offset += 1;
          list.push.apply(list, res.data.tags);
        }
        return res.data;
      });
    });

    var api = {
      reset: function () {
        list.length = 0;
        offset = 0;
        more = true;
        manageTagRemoteService.expireAll();
      },
      getMore: function (sort) {
        return manageTagRemoteService.get(sort, offset);
      },
      hasMore: function () {
        return more;
      },
      loadInitial: function () {
        list.length = 0;
        offset = 0;
        more = true;
        manageTagRemoteService.expireAll();

        var first = api.getMore();
        first.then(function () {
          return api.getMore();
        });

        if (list.length > 0) {
          return $q.when(list);
        }
        return first;
      },
      list: list
    };

    return api;
  }
]);
