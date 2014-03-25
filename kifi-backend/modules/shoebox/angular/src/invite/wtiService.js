'use strict';

angular.module('kifi.invite.wtiService', [])

.factory('wtiService', [
  '$http', 'routeService',
  function ($http, routeService) {
    var list = [];
    var more = true;
    var page = 0;
    var requestInProgress =  false;
    var lastRequest;
    var api = {
      getMore: function () {
        if (!requestInProgress) {
          requestInProgress = true;
          lastRequest = $http.get(routeService.whoToInvite + '?page=' + page).then(function (res) {
            requestInProgress = false;
            list.push.apply(list, res.data);
            if (res.data.length === 0) {
              more = false;
            } else {
              page = page + 1;
            }
            return list;
          });
        }
        return lastRequest;
      },
      hasMore: function () {
        return more && page < 5;
      },
      loadInitial: function () {
        if (list.length === 0) {
          api.getMore().then(function () {
            api.getMore();
          });
        }
      },
      list: list
    };

    return api;
  }
]);
