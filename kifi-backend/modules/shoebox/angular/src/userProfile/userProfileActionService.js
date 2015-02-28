'use strict';

angular.module('kifi')

.factory('userProfileActionService', [
  '$http', '$q', 'routeService', 'Clutch',
  function ($http, $q, routeService, Clutch) {
    var clutchParams = {
      cacheDuration: 10000
    };

    function getData(res) {
      return res.data;
    }

    var profileClutch = new Clutch(function (username) {
      return $http.get(routeService.getUserProfile(username)).then(getData);
    }, clutchParams);

    var librariesClutch = new Clutch(function (username, filter, page, size) {
      return $http.get(routeService.getUserLibraries(username, filter, page, size)).then(getData);
    }, clutchParams);

    var connectionsClutch = new Clutch(function (username, limit) {
      return $http.get(routeService.getUserConnections(username, limit)).then(getData);
    }, clutchParams);

    var connectionIdsClutch = new Clutch(function (username, limit) {
      return $http.get(routeService.getUserConnectionIds(username, limit)).then(getData);
    }, clutchParams);

    var api = {
      getProfile: angular.bind(profileClutch, profileClutch.get),
      getLibraries: angular.bind(librariesClutch, librariesClutch.get),
      getConnections: angular.bind(connectionsClutch, connectionsClutch.get),
      getConnectionsById: function (username, ids) {
        return $http.get(routeService.getUserConnectionsById(username, ids)).then(getData);
      },
      getConnectionIds: angular.bind(connectionIdsClutch, connectionIdsClutch.get)
    };

    return api;
  }
]);
