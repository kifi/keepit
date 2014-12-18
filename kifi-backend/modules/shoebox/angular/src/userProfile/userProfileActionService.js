'use strict';

angular.module('kifi')

.factory('userProfileActionService', [
  '$http', '$q', 'routeService', 'Clutch',
  function ($http, $q, routeService, Clutch) {
    var clutchParamsRecos = {
      cacheDuration: 2000
    };

    var userProfileService = new Clutch(function (username) {
      return $http.get(routeService.getUserProfile(username)).then(function (res) {
        if (res && res.data) {
          return res.data;
        }
      });
    }, clutchParamsRecos);

    var api = {
      getProfile: function (username) {
        return userProfileService.get(username);
      }
    };

    return api;
  }
]);
