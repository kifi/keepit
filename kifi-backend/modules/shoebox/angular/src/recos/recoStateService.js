'use strict';

angular.module('kifi')

.factory('recoStateService', [
  function () {
    var savedRecos = [];
    var recoUrls = {};

    var api = {
      recosList: savedRecos,

      populate: function (recos) {
        var populated = false;

        for (var i = 0; i < recos.length; i++) {
          var reco = recos[i];
          var url = (reco.recoLib || reco.recoKeep).url;
          if (!recoUrls[url]) {
            recoUrls[url] = true;
            savedRecos.push(reco);
            populated = true;
          }
        }

        return populated;
      },

      empty: function () {
        savedRecos.length = 0;
        recoUrls = {};
      }
    };

    return api;
  }
]);
