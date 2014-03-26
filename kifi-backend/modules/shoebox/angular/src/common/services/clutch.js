'use strict';

angular.module('kifi.clutch', [])

.factory('Clutch', ['$q', '$timeout', 'util',
  function ($q, $timeout, util) {

    var Clutch = (function () {

      var config, getter, _cache;

      var defaultConfig = {
        remoteError: 'ignore', // used
        cache: false, // todo
        cacheDuration: 30000, // used
        returnPreviousOnExpire: false, // used
        defaultValue: false, // todo
        somethingAboutOffline: true // todo
      };

      var now = Date.now || function () { return new Date().getTime(); };

      function Clutch(func, _config) {
        config = _.assign({}, defaultConfig, _config);
        getter = func;
        _cache = {};
      }

      // Returns a $q promise that will be resolved with the value
      // If value is cached, the promise is resolved immediately.
      Clutch.prototype.get = function () {
        var key = stringize(arguments);
        var hit = _cache[key];

        if (!hit) {
          // Never been refreshed.
          return refresh.call(this, key, arguments);
        } else if (!hit.value) {
          // Previous refresh did not finish.
          return hit.q;
        } else if (isExpired(hit.time, config.cacheDuration)) {
          // Value exists, but is expired.
          if (config.returnPreviousOnExpire) {
            // Trigger refresh, and return previous future
            refresh.apply(this, key, arguments);
            return $q.when(hit.value);
          }
          return refresh.call(this, key, arguments);
        }
        return hit.q || $q.when(hit.value);
      };

      Clutch.prototype.contains = function () {
        return !!_cache[stringize(arguments)];
      };

      Clutch.prototype.refresh = function () {
        var key = stringize(arguments);
        return refresh(key, arguments);
      };

      Clutch.prototype.age = function () {
        var key = stringize(arguments);
        return key && _cache[key] && now() - _cache[key].time;
      };

      Clutch.prototype.isExpired = function () {
        var key = stringize(arguments);
        var prev = key && _cache[key];
        return prev && isExpired(prev.time, config.cacheDuration);
      };

      Clutch.prototype.expire = function () {
        var key = stringize(arguments);
        var prev = _cache[key];
        delete _cache[key];
        return prev;
      };

      Clutch.prototype.expireAll = function () {
        _cache.length = 0;
        return;
      };

      //
      // Private helper functions
      //

      function stringize(args) {
        // todo check if already array
        return JSON.stringify(Array.prototype.slice.call(args));
      }

      function isExpired(hitTime, duration) {
        return now() - hitTime > duration;
      }

      function refresh(key, args) {
        var deferred = $q.defer();
        var resultQ = getter.apply(this, args);  // todo: check if getter returns a $q promise?

        var obj = _cache[key] || {};

        // Save the promise so we return the same promise if
        // multiple requests come in before it's resolved.
        obj.q = deferred.promise;

        _cache[key] = obj; // todo: needed?

        resultQ.then(function success(result) {
          obj.time = now();

          if (!obj.value) {
            // It's never been set before.
            obj.value = result;
            _cache[key] = obj;
          } else {
            if (angular.isArray(result)) {
              util.replaceArrayInPlace(obj.value, result);
            } else if (angular.isObject(result)) {
              util.replaceObjectInPlace(obj.value, result);
            } else {
              throw new TypeError('Supplied function must return an array/object');
            }
          }
          deferred.resolve(obj.value);
        })['catch'](function (reason) {
          if (obj.value && config.remoteError === 'ignore') {
            deferred.resolve(obj.value);
          } else {
            deferred.reject(reason);
          }
        });
        return deferred.promise;
      }

      return Clutch;

    })();

    return Clutch;

  }
]);
