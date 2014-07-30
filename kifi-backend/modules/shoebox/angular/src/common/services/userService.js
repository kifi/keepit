'use strict';

angular.module('kifi.userService', [
  'ngRoute',
  'kifi',
  'kifi.routeService'
])

.factory('userService', [
  '$http', '$q', 'routeService',
  function ($http, $q, routeService) {
    return {
      getBasicUserInfo: function (id, friendCount) {
        var deferred = $q.defer();
        $http.get(routeService.basicUserInfo(id, friendCount)).then(function (res) {
          deferred.resolve(res);
        }, function (res) {
          deferred.reject(res);
        });
        return deferred.promise;
      }
    };
  }
])

;
