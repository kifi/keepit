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
      return $http.get(routeService.getProfileConnections(username, limit)).then(getData);
    }, clutchParams);

    var followersClutch = new Clutch(function (username, limit) {
      return $http.get(routeService.getProfileFollowers(username, limit)).then(getData);
    }, clutchParams);

    return {
      getProfile: angular.bind(profileClutch, profileClutch.get),
      getLibraries: angular.bind(librariesClutch, librariesClutch.get),
      getConnections: angular.bind(connectionsClutch, connectionsClutch.get),
      getFollowers: angular.bind(followersClutch, followersClutch.get),
      getUsers: function (ids) {
        return $http.get(routeService.getProfileUsers(ids)).then(getData);
      }
    };
  }
]);
