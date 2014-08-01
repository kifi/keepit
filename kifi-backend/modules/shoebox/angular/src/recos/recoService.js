'use strict';

angular.module('kifi.recoService', ['util'])

.factory('recoService', [
  '$http', 'env', '$q', 'routeService', 'Clutch', 'util',
  function ($http, env, $q, routeService, Clutch, util) {

    var recos = [];
    var loading = false;

    var clutchParams = {
      cacheDuration: 20000
    };


    var adHocRecoService = new Clutch(function () {
      return $http.get(routeService.adHocRecos(25)).then(function (res) {
        res.data.forEach( function (keep) {
          keep.tagList = [];
        });
        util.replaceArrayInPlace(recos, res.data);
        return recos;
      });
    }, clutchParams);

    var api = {

      fetchAdHocRecos: function () {
        loading = true;
        adHocRecoService.get().then( function () {
          loading = false;
        });
      },

      recos: function () {
        return recos;
      },

      loading: function () {
        return loading;
      }

    };

    return api;
  }
]);
