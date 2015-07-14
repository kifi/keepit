'use strict';

angular.module('kifi')

.factory('orgProfileActionService', [
  '$http', '$q', 'routeService', 'Clutch',
  function ($http, $q, routeService, Clutch) {
    var clutchParams = {
      cacheDuration: 10000
    };

    function getData(res) {
      return res.data;
    }

    var profileClutch = new Clutch(function (handle) {
      return $http.get(routeService.getOrgProfile(handle)).then(getData);
    }, clutchParams);

    var librariesClutch = new Clutch(function (handle, filter, page, size) {
      return $http.get(routeService.getOrgLibraries(handle, filter, page, size)).then(getData);
    }, clutchParams);

    return {
      getProfile: angular.bind(profileClutch, profileClutch.get),
      getLibraries: angular.bind(librariesClutch, librariesClutch.get),

      getUsers: function (ids) {
        return $http.get(routeService.getOrgProfileUsers(ids)).then(getData);
      }
    };
  }
]);
