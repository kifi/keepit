'use strict';

angular.module('kifi')

.factory('hashtagService', [
  '$http', '$q', 'routeService', 'Clutch',
  function ($http, $q, routeService, Clutch) {
    var api = {};

    var clutchParams = {
      cacheDuration: 20000
    };

    var suggestTagsCache = new Clutch(function (keep, query) {
      var promise = $http.get(routeService.suggestTags(keep.libraryId, keep.id, query));
      var deferred = $q.defer();
      promise.then(function (res) {
        deferred.resolve(res.data);
      });
      return deferred.promise;
    }, clutchParams);

    api.suggestTags = function(keep, query) {
      return suggestTagsCache.get(keep, query);
    };

    api.tagKeep = function (keep, tag) {
      var promise = $http.post(routeService.tagKeep(keep.libraryId, keep.id, tag));
      var deferred = $q.defer();
      promise.then(function (res) {
        suggestTagsCache.expireAll();
        keep.addHashtag(tag);
        deferred.resolve(res.data);
      });
      return deferred.promise;
    };

    api.untagKeep = function (keep, tag) {
      var promise = $http['delete'](routeService.untagKeep(keep.libraryId, keep.id, tag));
      var deferred = $q.defer();
      promise.then(function (res) {
        suggestTagsCache.expireAll();
        keep.removeHashtag(tag);
        deferred.resolve(res.data);
      });
      return deferred.promise;
    };

    return api;
  }
])

;
