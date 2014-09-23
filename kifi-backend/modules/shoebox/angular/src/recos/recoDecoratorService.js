'use strict';

angular.module('kifi')

.factory('recoDecoratorService', ['keepDecoratorService', 'util',
  function (keepDecoratorService, util) {

    function Recommendation(rawReco, type) {
      this.recoData = {
        type: type,
        kind: rawReco.kind,
        reasons: rawReco.metaData || [],
        explain: rawReco.explain
      };

      this.recoData.reasons.forEach(function (reason) {
        if (!reason.name && reason.url) {
          reason.name = util.formatTitleFromUrl(reason.url);
        }
      });

      if (this.recoData.kind === 'keep') {
        this.recoKeep = new keepDecoratorService.Keep(rawReco.itemInfo, 'reco');
      } else {
        this.recoLib = rawReco.itemInfo;
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
