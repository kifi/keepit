'use strict';

angular.module('kifi')

// A wrapper around $location that updates the browser url without
// reloading or changing the $route state.
.factory('locationNoReload', ['$location', '$route', '$rootScope',
  function ($location, $route, $rootScope) {
    $location.skipReload = function () {
      var lastRoute = $route.current;
      var un = $rootScope.$on('$locationChangeSuccess', function () {
        $route.current = lastRoute;
        un();
      });

      return $location;
    };

    return $location;
}]);
