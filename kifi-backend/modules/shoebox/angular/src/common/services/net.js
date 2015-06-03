'use strict';

angular.module('kifi')

.factory('net', [
  'env', '$http', 'ExpiringCache',
  function (env, $http, ExpiringCache) {
    var shoebox = env.xhrBase;
    var search = env.xhrBaseSearch;
    var pathParamRe = /(:\w+)/;

    var post = angular.bind(null, http, 'POST');  // caller should pass any path params, optional post data (JSON), and an optional query params object
    var del = angular.bind(null, http, 'DELETE'); // caller should pass any path params and then, optionally, a query params object

    return {
      event: post(shoebox, '/events'),

      getLibraryInfos: get(shoebox, '/libraries', 30),
      getLibraryInfoById: get(shoebox, '/libraries/:id/summary', 30),
      getLibraryByUserSlug: get(shoebox, '/users/:username/libraries/:slug?showPublishedLibraries=1', 30),
      getLibraryById: get(shoebox, '/libraries/:id', 30),

      getKeep: get(shoebox, '/keeps/:id'),
      getKeepsInLibrary: get(shoebox, '/libraries/:id/keeps', 30),
      addKeepsToLibrary: post(shoebox, '/libraries/:id/keeps'),
      copyKeepsToLibrary: post(shoebox, '/libraries/copy'),
      moveKeepsToLibrary: post(shoebox, '/libraries/move'),
      removeKeepFromLibrary: del(shoebox, '/libraries/:id/keeps/:keepId'),
      removeManyKeepsFromLibrary: post(shoebox, '/libraries/:id/keeps/delete'),

      getLibraryShareSuggest: get(shoebox, '/libraries/:id/members/suggest?n=30', 30),
      updateLibraryMembership: post(shoebox, '/libraries/:id/members/:uid/access'),

      search: {
        search: get(search, '/search'),
        searched: post(search, '/search/events/searched'),
        resultClicked: post(search, '/search/events/resultClicked')
      }
    };

    function get(base, pathSpec, cacheSec) {
      var pathParts = pathSpec.split(pathParamRe);
      var cache = cacheSec && ExpiringCache(pathSpec, cacheSec);
      function doGet() {  // caller should pass any path params and then, optionally, a query params object
        return $http({
          method: 'GET',
          url: base + constructPath(pathParts, arguments),
          params: arguments[(pathParts.length - 1) / 2],
          cache: cache
        });
      }
      if (cache) {
        // Would also expose a method for removing a specific cache entry, but Angular
        // doesn't directly expose its functionality for building a request URL.
        // With our short-lived caches, clearing them entirely is good enough anyway.
        doGet.clearCache = angular.bind(cache, cache.removeAll);
      }
      return doGet;
    }

    function http(method, base, pathSpec) {
      var pathParts = pathSpec.split(pathParamRe);
      return function () {
        var path = constructPath(pathParts, arguments);
        var i = (pathParts.length - 1) / 2;
        var data = method === 'POST' ? arguments[i++] : undefined;
        var params = arguments[i++];
        return $http({
          method: method,
          url: base + path,
          params: params,
          data: data
        });
      };
    }

    function constructPath(pathParts, args) {
      if (pathParts.length === 1) {
        return pathParts[0];
      }
      var parts = pathParts.slice();
      for (var i = 1; i < pathParts.length; i += 2) {
        parts[i] = args[(i - 1) / 2];
      }
      return parts.join('');
    }
  }
]);
