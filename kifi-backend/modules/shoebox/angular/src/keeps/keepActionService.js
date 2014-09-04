'use strict';

angular.module('kifi')

.factory('keepActionService', [
  '$analytics',
  '$http',
  '$location',
  '$log',
  '$q',
  'env',
  'routeService',
  function ($analytics, $http, $location, $log, $q, env, routeService) {
    function keepMany(keeps, isPrivate) {
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
      $log.log('keepActionService.keep()', data);

      var url = env.xhrBase + '/keeps/add';
      var config = {
        params: { separateExisting: true }
      };

      return $http.post(url, data, config).then(function (res) {
        return (res && res.data && res.data.keeps) || [];
      });
    }

    function keepOne(keep, isPrivate) {
      return keepMany([keep], isPrivate).then(function (keeps) {
        return keeps[0];
      });
    }

    function togglePrivateMany(keeps) {
      // If all the keeps were private, they will all become public.
      // If all the keeps were public, they will all become private.
      // If some of the keeps were private and some public, they will all become private.
      return keepMany(keeps, !_.every(keeps, 'isPrivate'));
    }

    function togglePrivateOne(keep) {
      return togglePrivateMany([keep]);
    }

    function unkeep(keeps) {
      $analytics.eventTrack('user_clicked_page', {
        'action': 'unkeep',
        'path': $location.path()
      });

      if (!(keeps && keeps.length)) {
        return;
      }

      var url, data;

      if (keeps.length === 1 && keeps[0].id) {
        url = routeService.removeSingleKeep(keeps[0].id);
        data = {};
      } else {
        url = routeService.removeKeeps;
        data = _.map(keeps, function (keep) {
          return {
            url: keep.url
          };
        });
      }

      $log.log('keepService.unkeep()', url, data);

      return $http.post(url, data);
    }

    function unkeepOne(keep) {
      return unkeep([keep]);
    }

    var api = {
      keepOne: keepOne,
      togglePrivateOne: togglePrivateOne,
      unkeepOne: unkeepOne
    };

    return api;
  }
]);
