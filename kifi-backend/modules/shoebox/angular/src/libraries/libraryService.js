'use strict';

angular.module('kifi')

.factory('libraryService', [
  '$http', 'util', 'profileService', 'routeService', 'Clutch',
  function ($http, util, profileService, routeService, Clutch) {
    var libraries = [],
        invited = [];

    // var fuseOptions = {
    //   keys: ['name'],
    //   threshold: 0.3
    // };
    // var fuseSearch = new Fuse(libraries, fuseOptions);

    var librarySummariesService = new Clutch(function () {
      return $http.get(routeService.getLibrarySummaries).then(function (res) {
          util.replaceArrayInPlace(libraries, res.data.libraries || []);
          util.replaceArrayInPlace(invited, res.data.invited || []);
          return libraries;
      });
    });

    var libraryByIdService = new Clutch(function () {
      return $http.get(routeService.getLibrarySummaries).then(function (res) {
          util.replaceArrayInPlace(libraries, res.data.libraries || []);
          util.replaceArrayInPlace(invited, res.data.invited || []);
          return libraries;
      });
    });

    var libraryByPathService = new Clutch(function () {
      return $http.get(routeService.getLibrarySummaries).then(function (res) {
          util.replaceArrayInPlace(libraries, res.data.libraries || []);
          util.replaceArrayInPlace(invited, res.data.invited || []);
          return libraries;
      });
    });

    var api = {
      isAllowed: function () {
        return profileService.me.experiments && profileService.me.experiments.indexOf('libraries') !== -1;
      },
      libraries: libraries,
      invited: invited,
      fetchLibrarySummaries: function () {
        return librarySummariesService.get();
      },
      getLibraryById: function (libraryId) {
        return libraryByIdService.get(libraryId);
      },
      getLibraryByPath: function (path) {
        return libraryByPathService.get(path);
      }
    };

    return api;
  }
]);
