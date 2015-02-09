'use strict';

angular.module('kifi')

.factory('recoStateService', [
  function () {
    var savedRecos = [];
    var recoUrls = {};

    var api = {
      recosList: savedRecos,

      populate: function (recos) {
        for (var i = 0; i < recos.length; i++) {
          var reco = recos[i];
          var url = (reco.recoLib || reco.recoKeep).url;
          if (!recoUrls[url]) {
            recoUrls[url] = true;
            savedRecos.push(reco);
          }
        }
      },

      empty: function () {
        savedRecos.length = 0;
      }
    };

    return api;
  }
]);
