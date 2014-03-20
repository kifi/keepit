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
    return {
      getMore: function () {
        if (!requestInProgress) {
          requestInProgress = true;
          lastRequest = $http.get(routeService.whoToInvite + '?page=' + page).then(function (res) {
            requestInProgress = false;
            list = list.concat(res.data);
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
      reset: function () {
        list = [];
        more = true;
        page = 0;
        requestInProgress = false;
        lastRequest = null;
      }
    };
  }
]);
