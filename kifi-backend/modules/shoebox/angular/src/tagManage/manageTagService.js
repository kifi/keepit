'use strict';

angular.module('kifi')

.factory('manageTagService', [
  '$http', 'routeService', 'Clutch',
  function ($http, routeService, Clutch) {
    var pageSize = 10;

    var manageTagRemoteService = new Clutch(function (sort, offset) {
      return $http.get(routeService.pageTags + '?sort=' + sort + '&offset=' + offset + '&pageSize=' + pageSize
        ).then(function (res) {
          return res.data.tags;
        }
      );
    });

    var api = {
      reset: function () {
        manageTagRemoteService.expireAll();
      },
      getMore: function (sort, offset) {
        return manageTagRemoteService.get(sort, offset);
      }
    };

    return api;
  }
]);
