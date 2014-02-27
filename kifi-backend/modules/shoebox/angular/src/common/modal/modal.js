'use strict';

angular.module('kifi.modal', ['ui.bootstrap'])

.factory('kfModal', [
  '$modal',
  function ($modal) {
    return {
      open: function (opts) {

        $modal.open(opts);
      }
    };
  }
]);
