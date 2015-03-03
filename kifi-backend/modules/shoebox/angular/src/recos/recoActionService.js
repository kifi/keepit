'use strict';

angular.module('kifi')

.factory('recoActionService', [
  '$http', '$q', 'routeService', 'Clutch',
  function ($http, $q, routeService, Clutch) {
    var rawRecos = [];
    var rawPopularRecos = [];
    var uriContext, libContext;

    var clutchParamsRecos = {
      cacheDuration: 2000
    };

    var kifiRecommendationService = new Clutch(function (opts) {
      var recoOpts = {
        recency: opts && angular.isNumber(opts.recency) ? opts.recency : 0.75,
        trackLibDelivery: opts && _.isBoolean(opts.trackLibDelivery) ? opts.trackLibDelivery : undefined
      };

      if (opts && opts.more) {
        recoOpts.uriContext = uriContext;
        recoOpts.libContext = libContext;
      }

      return $http.get(routeService.recos(recoOpts)).then(function (res) {
        if (res && res.data) {
          // Save recommendation data from the backend so we don't have to
          // fetch them again on Angular route reload of recommendations.
          rawRecos = res.data.recos;
          uriContext = res.data.uctx;
          libContext = res.data.lctx;
          return res.data.recos;
        }
      });
    }, clutchParamsRecos);

    var clutchParamsPopularRecos = {
      cacheDuration: 2000
    };

    var kifiPopularRecommendationService = new Clutch(function () {
      return $http.get(routeService.recosPublic()).then(function (res) {
        if (res && res.data) {
          // Save popular recommendation data from the backend so we don't have to
          // fetch them again on Angular route reload of popular recommendations.
          rawPopularRecos = res.data;
          return res.data;
        }
      });
    }, clutchParamsPopularRecos);

    function recoFeedbackUrl(reco) {
      if (reco.recoData.kind === 'library') {
        return routeService.libraryRecoFeedback(reco.recoLib.id);
      } else {
        return routeService.recoFeedback(reco.recoKeep.urlId);
      }
    }

    var api = {
      get: function (invalidate, trackLibDelivery) {
        if (invalidate || rawRecos.length === 0) {
          uriContext = '';
          libContext = '';
          var opts = {trackLibDelivery: trackLibDelivery};
          return kifiRecommendationService.get(opts);
        } else {
          return $q.when(rawRecos);
        }
      },

      getMore: function (opt_recency) {
        return kifiRecommendationService.get({ more: true, recency: opt_recency });
      },

      getPopular: function () {
        return rawPopularRecos.length > 0 ?
          $q.when(rawPopularRecos) :
          kifiPopularRecommendationService.get();
      },

      trash: function (reco) {
        $http.post(recoFeedbackUrl(reco), { trashed: true });
      },

      vote: function (reco, vote) {
        // vote === true -> upvote; vote === false -> downvote
        $http.post(recoFeedbackUrl(reco), { vote: vote });
      },

      trackKeep: function (recoKeep) {
        $http.post(routeService.recoFeedback(recoKeep.urlId), { kept: true });
      },

      trackFollow: function (recoLib) {
        $http.post(routeService.libraryRecoFeedback(recoLib.id), { followed: true });
      },

      trackClick: function (reco) {
        $http.post(recoFeedbackUrl(reco), { clicked: true });
      },

      improve: function (recoKeep, improvement) {
        $http.post(routeService.recoFeedback(recoKeep.urlId), { comment: improvement });
      }
    };

    return api;
  }
]);
