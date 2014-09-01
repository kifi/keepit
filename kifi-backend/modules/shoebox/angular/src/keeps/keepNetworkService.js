'use strict';

angular.module('kifi')

.factory('keepNetworkService', [
  '$analytics',
  '$http',
  '$location',
  '$log',
  '$q',
  'env',
  function ($analytics, $http, $location, $log, $q, env) {
    function keepMany (keeps, isPrivate) {
      $analytics.eventTrack('user_clicked_page', {
          'action': 'keep',
          'path': $location.path()
        });

      if (!(keeps && keeps.length)) {
        return $q.when(keeps || []);
      }

      var data = {
        keeps: keeps.map(function (keep) {
          if (_.isBoolean(isPrivate)) {
            keep.isPrivate = isPrivate;
          }

          return {
            title: keep.title,
            url: keep.url,
            isPrivate: keep.isPrivate
          };
        })
      };
      $log.log('keepService.keep()', data);

      var url = env.xhrBase + '/keeps/add';
      var config = {
        params: { separateExisting: true }
      };

      return $http.post(url, data, config).then(function (res) {
        return (res && res.data && res.data.keeps) || [];
      });
    }

    function keepPublic (keep) {
      return keepMany([keep], false).then(function (keeps) {
        return keeps[0];
      });
    }

    var api = {
      keepPublic: keepPublic
    };

    return api;
  }
]);
