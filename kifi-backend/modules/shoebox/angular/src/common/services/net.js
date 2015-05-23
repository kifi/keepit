'use strict';

angular.module('kifi')

.factory('net', [
  'env', '$http',
  function (env, $http) {
    var shoebox = env.xhrBase;
    var search = env.xhrBaseSearch;
    var pathParamRe = /(:\w+)/;

    var get = angular.bind(null, http, 'GET');    // caller should pass any path params and then, optionally, a query params object
    var post = angular.bind(null, http, 'POST');  // caller should pass any path params, optional post data (JSON), and an optional query params object
    var del = angular.bind(null, http, 'DELETE'); // caller should pass any path params and then, optionally, a query params object

    return {
      event: post(shoebox, '/events'),

      getKeep: get(shoebox, '/keeps/:id'),
      addKeepsToLibrary: post(shoebox, '/libraries/:id/keeps'),
      copyKeepsToLibrary: post(shoebox, '/libraries/copy'),
      moveKeepsToLibrary: post(shoebox, '/libraries/move'),
      removeKeepFromLibrary: del(shoebox, '/libraries/:id/keeps/:keepId'),
      removeManyKeepsFromLibrary: post(shoebox, '/libraries/:id/keeps/delete'),

      updateLibraryMembership: post(shoebox, '/libraries/:id/members/:uid/access'),

      search: {
        search: get(search, '/search'),
        searched: post(search, '/search/events/searched'),
        resultClicked: post(search, '/search/events/resultClicked')
      }
    };

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
