'use strict';

angular.module('kifi.undo', [])

.factory('undoService', [
  '$timeout',
  function ($timeout) {

    var DEFAULT_DURATION = 5000;

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
        if (api.callback) {
          api.callback();
        }
        api.clear();
      }
    };

    return api;
  }
]);
