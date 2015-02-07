'use strict';

angular.module('kifi')

.factory('recoStateService', [
  function () {
    var savedRecos = [];

    var api = {
      recosList: savedRecos,

      populate: function (recos) {
        _.remove(recos, function (reco) {
          return _.some(savedRecos, function (savedReco) {
            return (
              reco.recoLib && savedReco.recoLib && (reco.recoLib.url === savedReco.recoLib.url) ||
              reco.recoKeep && savedReco.recoKeep && (reco.recoKeep.url === savedReco.recoKeep.url)
            );
          });
        });
        savedRecos.push.apply(savedRecos, recos);
      },

      empty: function () {
        savedRecos.length = 0;
      }
    };

    return api;
  }
]);
