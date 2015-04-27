'use strict';

angular.module('kifi')

.factory('undoService', [
  '$timeout',
  function ($timeout) {
    var message = null;
    var undo;
    var timeout;

    function clear() {
      if (timeout) {
        $timeout.cancel(timeout);
      }
      message = undo = timeout = null;
    }

    return {
      add: function (messageText, undoFunction, duration) {
        clear();
        message = messageText;
        undo = undoFunction;
        timeout = $timeout(function () {
          timeout = null;
          clear();
        }, duration || 30000);
      },
      clear: clear,
      getMessage: function () {
        return message;
      },
      undo: function () {
        var res = undo && undo.call();
        clear();
        return res;
      }
    };
  }
]);
