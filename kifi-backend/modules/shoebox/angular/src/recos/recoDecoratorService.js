'use strict';

angular.module('kifi')

.factory('recoDecoratorService', ['cardService', 'util',
  function (cardService, util) {
    function Recommendation(rawReco, type) {
      this.recoData = {
        type: type,
        kind: rawReco.kind,
        reasons: rawReco.metaData || []
      };

      this.recoData.reasons.forEach(function (reason) {
        if (!reason.name) {
          reason.name = util.formatTitleFromUrl(reason.url);
        }
      });

      this.card = new cardService.Card(rawReco.itemInfo, 'reco');
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
