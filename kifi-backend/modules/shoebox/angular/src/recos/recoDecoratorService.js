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
