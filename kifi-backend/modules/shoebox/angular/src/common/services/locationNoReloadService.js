'use strict';

angular.module('kifi')

// A wrapper around $location that updates the browser url without
// reloading or changing the $route state.
.factory('locationNoReload', ['$location', '$route', '$rootScope', '$timeout',
  function ($location, $route, $rootScope, $timeout) {
    var cancelReloadNextRouteChange = null;

    $location.skipReload = function () {
      var lastRoute = $route.current;

      var un = $rootScope.$on('$locationChangeSuccess', function () {
        $route.current = lastRoute;
        un();
      });

      return $location;
    };

    $location.reloadNextRouteChange = function () {
      $timeout(function () {
        cancelReloadNextRouteChange = $rootScope.$on('$locationChangeSuccess', function () {
          if (cancelReloadNextRouteChange) {
            $route.reload();
            cancelReloadNextRouteChange();
            cancelReloadNextRouteChange = null;
          }
        });
      }, 0);
    };

    $location.cancelReloadNextRouteChange = function () {
      if (cancelReloadNextRouteChange) {
        cancelReloadNextRouteChange();
        cancelReloadNextRouteChange = null;
      }
    };

    return $location;
}]);
