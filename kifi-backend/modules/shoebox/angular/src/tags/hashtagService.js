'use strict';

angular.module('kifi')

.factory('hashtagService', [
  '$http', '$q', 'routeService', 'Clutch',
  function ($http, $q, routeService, Clutch) {
    var clutchParams = {
      cacheDuration: 20000
    };

    var suggestClutch = new Clutch(function (keep, query) {
      var promise = $http.get(routeService.suggestTags(keep.libraryId, keep.id, query));
      var deferred = $q.defer();
      promise.then(function (res) {
        deferred.resolve(res.data);
      });
      return deferred.promise;
    }, clutchParams);

    return {
      suggestTags: function(keep, query) {
        return suggestClutch.get(keep, query);
      }
    };
  }
]);
