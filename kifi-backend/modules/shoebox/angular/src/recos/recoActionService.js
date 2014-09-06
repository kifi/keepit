'use strict';

angular.module('kifi')

.factory('recoActionService', [
  '$http', '$q', 'routeService', 'Clutch',
  function ($http, $q, routeService, Clutch) {
    var rawRecos = [];
    var rawPopularRecos = [];

    var clutchParamsRecos = {
      cacheDuration: 2000
    };

    var kifiRecommendationService = new Clutch(function (opts) {
      var recoOpts = {
        more: (!opts || opts.more === undefined) ? false : opts.more,
        recency: opts && angular.isNumber(opts.recency) ? opts.recency : 0.75
      };

      return $http.get(routeService.recos(recoOpts)).then(function (res) {
        if (res && res.data) {
          // Save recommendation data from the backend so we don't have to
          // fetch them again on Angular route reload of recommendations.
          rawRecos = res.data;
          return res.data;
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

    var api = {
      get: function () {
        return rawRecos.length > 0 ? 
          $q.when(rawRecos) :
          kifiRecommendationService.get();
      },

      getMore: function (opt_recency) {
        return kifiRecommendationService.get({ more: true, recency: opt_recency });
      },

      getPopular: function () {
        return rawPopularRecos.length > 0 ?
          $q.when(rawPopularRecos) :
          kifiPopularRecommendationService.get();
      },

      trash: function (card) {
        $http.post(routeService.recoFeedback(card.urlId), { trashed: true });
      },

      vote: function (card, vote) {
        // vote === true -> upvote; vote === false -> downvote
        $http.post(routeService.recoFeedback(card.urlId), { vote: vote });
      },

      trackKeep: function (card) {
        $http.post(routeService.recoFeedback(card.urlId), { kept: true });
      },

      trackClick: function (card) {
        $http.post(routeService.recoFeedback(card.urlId), { clicked: true });
      },

      improve: function (card, improvement) {
        $http.post(routeService.recoFeedback(card.urlId), { comment: improvement });
      }
    };

    return api;
  }
]);
