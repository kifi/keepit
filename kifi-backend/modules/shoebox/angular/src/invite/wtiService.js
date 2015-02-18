'use strict';

angular.module('kifi')

.factory('wtiService', [
  '$http', 'routeService', 'Clutch', '$q',
  function ($http, routeService, Clutch, $q) {
    var list = [];
    var more = true;
    var offset = 0;
    var pageSize = 20;

    var wtiRemoteService = new Clutch(function (pageToGet) {
      return $http.get(routeService.whoToInvite + '?offset=' + pageToGet + '&limit=' + pageSize
        ).then(function (res) {
        if (res.data.length === 0) {
          more = false;
        } else {
          offset += pageSize;
          res.data = _.filter(res.data, function(r) {
            return r.network !== 'twitter';
          });
          list.push.apply(list, res.data);
        }
        return res.data;
      });
    });

    var api = {
      getMore: function () {
        return wtiRemoteService.get(offset);
      },
      hasMore: function () {
        return more;
      },
      loadInitial: function () {
        list.length = 0;
        offset = 0;
        more = true;
        wtiRemoteService.expireAll();

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
