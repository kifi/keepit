'use strict';

angular.module('kifi')

.factory('feedService', [
  '$http', '$rootScope', 'profileService', 'routeService', '$q', 'net', 'ml',
  function ($http, $rootScope, profileService, routeService, $q, net, ml) {

    function invalidateFeedCache() {
      [
        net.getKeepStream
      ].forEach(function (endpoint) {
        endpoint.clearCache();
      });
    }

    [
      'keepAdded',
      'keepRemoved',
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
      getFeed: function (limit, beforeId, afterId, filter) {
        ml.specs.getsFeed = new ml.Spec([
          new ml.Assert('Feed retrieved in 5 seconds or less', 5000),
          new ml.Expect('Feed returns a list', function(data) { return typeof data.length !== 'undefined'; })
        ]);

        var limitOpt = limit && { limit: limit }; // limit should be omitted if null, so pass it in the optional query param object
        return net.getKeepStream(beforeId || '', afterId || '', (filter || {}).value, (filter || {}).id, limitOpt)
        .then(function (response) {
          ml.specs.getsFeed.respond([null, response.data.keeps]);
          return response.data;
        });
      }
    };

    return api;
  }
]);
