'use strict';

angular.module('kifi.undo', [])

.factory('undoService', [
  '$timeout',
  function ($timeout) {

    var DEFAULT_DURATION = 30000;

    var api = {
      isSet: function () {
        return !!api.callback;
      },
      add: function (message, callback, duration) {
        api.message = message;
        api.callback = callback;

        if (api.promise) {
          $timeout.cancel(api.promise);
        }

        api.promise = $timeout(function () {
          api.promise = null;
          api.clear();
        }, duration == null ? DEFAULT_DURATION : duration);
      },
      clear: function () {
        if (api.promise) {
          $timeout.cancel(api.promise);
        }
        api.message = api.callback = api.promise = null;
      },
      undo: function () {
        var res = null;
        if (api.callback) {
          res = api.callback.call();
        }
        api.clear();

        return res;
      }
    };

    return api;
  }
]);
