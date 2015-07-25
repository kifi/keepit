'use strict';

angular.module('kifi')

.factory('Clutch', ['$q', '$timeout', 'util', '$window',
  function ($q, $timeout, util, $window) {

    var defaultConfig = {
      remoteError: 'ignore',
      // TODO: cache: false,
      cacheDuration: 30000,
      returnPreviousOnExpire: false
      // TODO: defaultValue: false,
      // TODO: somethingAboutOffline: ??
    };

    var now = Date.now || function () { return new Date().getTime(); };

    function Clutch(func, config) {
      this._config = _.defaults({}, config, defaultConfig);
      this._getter = func;
      this._cache = {};
    }

    // Returns a $q promise that will be resolved with the value
    // If value is cached, the promise is resolved immediately.
    Clutch.prototype.get = function () {
      var key = stringize(arguments);
      var hit = this._cache[key];

      if (!hit || (!hit.value && !hit.activeRequest)) {
        // Never been refreshed.
        return refresh.call(this, key, arguments);
      } else if (hit.activeRequest) {
        // Previous refresh did not finish.
        return hit.q;
      } else if (isExpired(hit.time, this._config.cacheDuration)) {
        // Value exists, but is expired.
        if (this._config.returnPreviousOnExpire) {
          // Trigger refresh, and return previous future
          refresh.call(this, key, arguments);
          return $q.when(hit.value);
        }
        return refresh.call(this, key, arguments);
      }
      return hit.q || $q.when(hit.value);
    };

    Clutch.prototype.contains = function () {
      return !!this._cache[stringize(arguments)];
    };

    Clutch.prototype.refresh = function () {
      var key = stringize(arguments);
      return refresh.call(this, key, arguments);
    };

    Clutch.prototype.age = function () {
      var key = stringize(arguments);
      return key && this._cache[key] && now() - this._cache[key].time;
    };

    Clutch.prototype.isExpired = function () {
      var key = stringize(arguments);
      var prev = key && this._cache[key];
      return prev && isExpired(prev.time, this._config.cacheDuration);
    };

    Clutch.prototype.expire = function () {
      var key = stringize(arguments);
      var prev = this._cache[key];
      if (this._cache[key]) {
        this._cache[key].time = 0;
      }
      return prev;
    };

    Clutch.prototype.expireAll = function () {
      _.forEach(this._cache, function (v) {
        v.time = 0;
      });
      return;
    };

    //
    // Private helper functions
    //

    function stringize(args) {
      // todo check if already array
      return $window.JSON.stringify(Array.prototype.slice.call(args));
    }

    function isExpired(hitTime, duration) {
      return now() - hitTime > duration;
    }

    // call by setting this correctly
    function refresh(key, args) {
      var deferred = $q.defer();
      var resultQ = this._getter.apply(this, args);  // todo: check if getter returns a $q promise?

      var obj = this._cache[key] || {};

      // Save the promise so we return the same promise if
      // multiple requests come in before it's resolved.
      obj.q = deferred.promise;
      obj.activeRequest = true;

      this._cache[key] = obj; // todo: needed?
      var that = this;

      resultQ.then(function success(result) {
        obj.time = now();
        obj.activeRequest = false;

        if (!obj.value) {
          // It's never been set before.
          obj.value = result;
          that._cache[key] = obj;
        } else {
          if (obj.value === result) {
            // Nothing to do, getter handled it
            return deferred.resolve(obj.value);
          } else if (angular.isArray(result)) {
            util.replaceArrayInPlace(obj.value, result);
          } else if (angular.isObject(result)) {
            util.replaceObjectInPlace(obj.value, result);
          } else {
            throw new TypeError('Supplied function must return an array/object');
          }
        }
        deferred.resolve(obj.value);
      })['catch'](function (reason) {
        obj.activeRequest = false;
        if (obj.value && that._config.remoteError === 'ignore') {
          deferred.resolve(obj.value);
        } else {
          deferred.reject(reason);
        }
      });
      return deferred.promise;
    }

    return Clutch;
  }
]);
