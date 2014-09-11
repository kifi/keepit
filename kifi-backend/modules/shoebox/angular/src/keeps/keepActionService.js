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
  'Clutch',
  function ($analytics, $http, $location, $log, $q, env, routeService, Clutch) {
    var limit = 10;
    var smallLimit = 4;

    var keepList = new Clutch(function (url, config) {
      $log.log('keepActionService.getList()', config && config.params);

      return $http.get(url, config).then(function (res) {
        return res && res.data;
      });
    });

    function getKeeps(lastKeepId, params) {  // TODO: what are these params?
      var url = env.xhrBase + '/keeps/all';

      params = params || {};
      params.withPageInfo = true;

      // Pass the id of the last keep received to the server so the server 
      // knows which keeps to send next.
      params.before = lastKeepId;

      // Request a smaller number of keeps in the first request.
      params.count = lastKeepId ? params.count || limit : smallLimit;

      var config = {
        params: params
      };

      return keepList.get(url, config).then(function (data) {
        var result = {};

        result.keeps = data.keeps;
        result.mayHaveMore = (data.keeps.length > 0) && (data.keeps.length >= params.count - 1);

        return result;
      });
    }

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

    function unkeepMany(keeps) {
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

      $log.log('keepActionService.unkeep()', url, data);

      return $http.post(url, data);
    }

    function unkeepOne(keep) {
      return unkeepMany([keep]);
    }

    var api = {
      getKeeps: getKeeps,
      keepOne: keepOne,
      keepMany: keepMany,
      togglePrivateOne: togglePrivateOne,
      togglePrivateMany: togglePrivateMany,
      unkeepOne: unkeepOne,
      unkeepMany: unkeepMany
    };

    return api;
  }
]);
