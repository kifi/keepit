'user strict';

angular.module('kifi')

.provider('$twitter', function $twttrProvider() {
  'use strict';

  /*
   * Options
   */

  var that = this,
    options = {
      // Default option values
    };

  function getSetOption(name, val) {
    if (val === void 0) {
      return options[name];
    }
    options[name] = val;
    return that;
  }


  this.option = function (name, val) {
    if (typeof name === 'object') {
      angular.extend(options, name);
      return that;
    }
    return getSetOption(name, val);
  };

  var twttr, twttrPromise, $window, $timeout, $q;

  /*
   * Initialization
   */

  this.$get = [
    '$window', '$timeout', '$q',
    function ($$window, $$timeout, $$q) {
      $q = $$q;
      $window = $$window;
      $timeout = $$timeout;
      return that;
    }
  ];

  /*
   * Public APIs
   */
  this.loading = false;
  this.loaded = false;
  this.twttr = null;
  this.failedToLoad = false;

  this.load = function () {
    if (!twttrPromise) {
      var window = $window,
        deferred = $q.defer();

      var failedToLoadHander = function (e, b) {
        if (!that.twttr && that.loading && !that.loaded) {
          that.loading = false;
          that.failedToLoad = true;
          document.getElementById('twitter-wjs').remove();
          deferred.reject({'error': 'no_twitter_on_page'});
          twttrPromise = null;
        }
      };

      window.twttr = (function (d, s, id) {
        var t, js, fjs = d.getElementsByTagName(s)[0];
        if (d.getElementById(id)) { return; }
        js = d.createElement(s); js.id = id;
        js.src= 'https://platform.twitter.com/widgets.js';
        js.addEventListener('error', failedToLoadHander);
        fjs.parentNode.insertBefore(js, fjs);
        return window.twttr || (t = { _e: [], ready: function (f) { t._e.push(f); } });
      }(document, 'script', 'twitter-wjs'));

      window.twttr.ready = function () {
        twttr = that.twttr = window.twttr;
        that.loading = false;
        that.loaded = true;

        $timeout(function () {
          deferred.resolve(twttr);
        });
      };

      that.loading = true;

      twttrPromise = deferred.promise;
    }
    return twttrPromise;
  };

  this.refreshWidgets = function () {
    return twttr.widgets.load();
  };

  this.initialized = false;


});
