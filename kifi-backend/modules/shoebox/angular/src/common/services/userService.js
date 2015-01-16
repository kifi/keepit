'use strict';

angular.module('kifi')

.factory('userService', [
  '$http', '$q', '$rootScope', '$stateParams', 'routeService',
  function ($http, $q, $rootScope, $stateParams, routeService) {
    return {
      getBasicUserInfo: function (id, friendCount) {
        var deferred = $q.defer();
        $http.get(routeService.basicUserInfo(id, friendCount)).then(function (res) {
          deferred.resolve(res);
        }, function (res) {
          deferred.reject(res);
        });
        return deferred.promise;
      },

      // Returns true if the user is in user profiles beta experiment or if the
      // user profiles beta query parameter is set. Temporarily lodged here because
      // code that needs this also usually needs userService.
      inUserProfileBeta: function () {
        return true;
      }
    };
  }
]);
