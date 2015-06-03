'use strict';

angular.module('kifi')

// Like Clutch, but built on $cacheFactory, so less code and interoperable with $http.
.factory('createExpiringCache', [
  '$cacheFactory',
  function ($cacheFactory) {
    function createExpiringCache(name, seconds) {
      var cache = $cacheFactory(name);
      cache.put = angular.bind(cache, expiringPut, cache.put);
      cache.get = angular.bind(cache, expiringGet, cache.get, seconds * 1000);
      return cache;
    }

    function expiringPut(put, key, value) {
      put.call(this, key, {value: value, time: Date.now()});
    }

    function expiringGet(get, ms, key) {
      var hit = get.call(this, key);
      if (hit) {
        if (Date.now() - hit.time > ms) {
          this.remove(key);
        } else {
          return hit.value;
        }
      }
    }

    return createExpiringCache;
  }
]);
