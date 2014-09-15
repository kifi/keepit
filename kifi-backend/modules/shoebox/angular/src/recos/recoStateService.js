'use strict';

angular.module('kifi')

.factory('recoStateService', [
  function () {
    var savedRecos = [];

    var api = {
      recosList: savedRecos,

      populate: function (recos) {
        savedRecos.push.apply(savedRecos, recos);
      },

      empty: function () {
        savedRecos.length = 0;
      }
    };

    return api;
  }
]);
