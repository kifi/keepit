'use strict';

angular.module('kifi')

.factory('recoDecoratorService', [
  'keepDecoratorService',
  function (keepDecoratorService) {

    function Recommendation(rawReco, type) {
      this.recoData = {
        type: type,  // This is either 'recommended' or 'popular'.
        kind: rawReco.kind,  // This is either 'keep' or 'library'.
        explain: rawReco.explain
      };

      if (this.recoData.kind === 'keep') {
        rawReco.itemInfo.libraries = rawReco.itemInfo.libraries;
        var libUsers = {};
        rawReco.itemInfo.libraries.forEach( function (lib) {
          lib.keeperName = lib.owner.firstName + ' ' + lib.owner.lastName;
          libUsers[lib.owner.id] = true;
        });
        //don't show a face only if there is also a library for that person
        rawReco.itemInfo.keepers.forEach(function (keeper) {
          if (libUsers[keeper.id]) {
            keeper.hidden = true;
          }
        });
        this.recoKeep = new keepDecoratorService.Keep(rawReco.itemInfo, 'reco');
      } else {
        this.recoLib = rawReco.itemInfo;

        // All recommended libraries are published user-created libraries.
        this.recoLib.kind = 'user_created';
        this.recoLib.visibility = 'published';
        this.recoLib.access = 'none';
      }
    }

    var api = {
      newUserRecommendation: function (rawReco) {
        return new Recommendation(rawReco, 'recommended');
      },

      newPopularRecommendation: function (rawReco) {
        return new Recommendation(rawReco, 'popular');
      }
    };

    return api;
  }
]);
