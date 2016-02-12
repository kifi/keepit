'use strict';

angular.module('kifi')

.factory('userProfileActionService', [
  '$http', '$q', 'routeService', 'Clutch', 'net',
  function ($http, $q, routeService, Clutch, net) {
    var clutchParams = {
      cacheDuration: 10000
    };

    function getData(res) {
      return res.data;
    }

    var profileClutch = new Clutch(function (handle) {
      return $http.get(routeService.getUserProfile(handle)).then(getData);
    }, clutchParams);

    var librariesClutch = new Clutch(function (handle, filter, page, size) {
      return $http.get(routeService.getUserLibraries(handle, filter, page, size)).then(getData);
    }, clutchParams);

    var connectionsClutch = new Clutch(function (handle, limit) {
      return $http.get(routeService.getProfileConnections(handle, limit)).then(getData);
    }, clutchParams);

    var followersClutch = new Clutch(function (handle, limit) {
      return $http.get(routeService.getProfileFollowers(handle, limit)).then(getData);
    }, clutchParams);

    // optArgs {
    //  ordering: "alphabetical" | "most_recent_keeps_by_user"
    //  direction: "asc" | "desc"
    //  window_size: #days (used for most_recent_keeps_by_user)
    // }
    var basicLibrariesClutch = new Clutch( function(id, offset, limit, optArgs) {
        return net.getBasicLibraries(id, offset, limit, optArgs).then(getData);
    }, clutchParams);

    return {
      getProfile: angular.bind(profileClutch, profileClutch.get),
      getLibraries: angular.bind(librariesClutch, librariesClutch.get),
      getConnections: angular.bind(connectionsClutch, connectionsClutch.get),
      getFollowers: angular.bind(followersClutch, followersClutch.get),
      getBasicLibraries: angular.bind(basicLibrariesClutch, basicLibrariesClutch.get),
      expireAllPeople: function () {
        connectionsClutch.expireAll();
        followersClutch.expireAll();
      },
      getUsers: function (ids) {
        return $http.get(routeService.getProfileUsers(ids)).then(getData);
      }
    };
  }
]);
