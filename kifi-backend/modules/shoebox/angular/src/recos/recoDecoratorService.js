'use strict';

angular.module('kifi')

.factory('recoDecoratorService', [
  'keepDecoratorService',
  function (keepDecoratorService) {

    function Recommendation(rawReco, type) {
      this.recoData = {
        type: type,  // 'recommended' or 'popular'
        kind: rawReco.kind  // 'keep' or 'library'
      };

      var info = rawReco.itemInfo;
      if (rawReco.kind === 'keep') {
        // TODO: update server to pass 'urlId' instead of 'id'
        info.urlId = info.id;
        delete info.id;

        this.recoKeep = new keepDecoratorService.Keep(info, 'reco');
      } else {
        this.recoLib = info;

        // All recommended libraries are published user-created libraries.
        this.recoLib.kind = 'user_created';
        this.recoLib.visibility = 'published';
        this.recoLib.access = 'none';
      }
    }

    return {
      newUserRecommendation: function (rawReco) {
        return new Recommendation(rawReco, 'recommended');
      },

      newPopularRecommendation: function (rawReco) {
        return new Recommendation(rawReco, 'popular');
      }
    };
  }
]);
