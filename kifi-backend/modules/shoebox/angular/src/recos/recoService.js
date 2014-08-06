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


    var adHocRecoService = new Clutch(function (weights) {
      return $http.post(routeService.adHocRecos(50), weights || {}).then(function (res) {
        res.data.forEach( function (keep) {
          keep.tagList = [];
        });
        util.replaceArrayInPlace(recos, res.data);
        return recos;
      });
    }, clutchParams);

    var api = {

      fetchAdHocRecos: function (weights) {
        loading = true;
        recos.length = 0;
        adHocRecoService.get(weights).then( function () {
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
