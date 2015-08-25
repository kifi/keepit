'use strict';

angular.module('kifi')

.factory('feedService', [
  '$http', '$rootScope', 'profileService', 'routeService', '$q', '$analytics', 'net', 'ml',
  function ($http, $rootScope, profileService, routeService, $q, $analytics, net, ml) {


    var api = {
      getFeed: function (limit, beforeId, afterId) {
        ml.specs.getsFeed = new ml.Spec([
          new ml.Assert('Feed retrieved in 3 seconds or less', 3000),
          new ml.Expect('Feed returns a list', function(data) { return data.length; })
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
