'use strict';

angular.module('kifi')

.factory('feedService', [
  '$http', '$rootScope', 'profileService', 'routeService', '$q', '$analytics', 'net', 'ml',
  function ($http, $rootScope, profileService, routeService, $q, $analytics, net, ml) {

    function invalidateFeedCache() {
      [
        net.getKeepStream
      ].forEach(function (endpoint) {
        endpoint.clearCache();
      });
    }

    [
      'keepAdded',
      'libraryJoined',
      'libraryLeft',
      'libraryDeleted',
      'libraryKeepCountChanged'
    ].forEach(function (feedItemEvent) {
      $rootScope.$on(feedItemEvent, function () {
        invalidateFeedCache();
      });
    });

    var api = {
      getFeed: function (limit, beforeId, afterId) {
        ml.specs.getsFeed = new ml.Spec([
          new ml.Assert('Feed retrieved in 5 seconds or less', 5000),
          new ml.Expect('Feed returns a list', function(data) { return typeof data.length !== 'undefined'; })
        ]);

        return net.getKeepStream(limit, beforeId, afterId)
        .then(function (response) {
          ml.specs.getsFeed.respond([null, response.data.keeps]);
          return response.data;
        });
      }
    };

    return api;
  }
]);
