'use strict';

angular.module('kifi')

.factory('libraryService', [
  '$http', 'util', 'profileService', 'routeService', 'Clutch', '$q',
  function ($http, util, profileService, routeService, Clutch, $q) {
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

    var libraryByIdService = new Clutch(function (libraryId) {
      return $http.get(routeService.getLibraryById(libraryId));
    });

    var libraryByUserSlugService = new Clutch(function (username, slug) {
      return $http.get(routeService.getLibraryByUserSlug(username, slug));
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
      getLibraryByPath: function (path) { // path is of the form /username/library-slug
        var split = path.split('/').filter(function (a) { return a.length !== 0; });
        var username = split[0];
        var slug = split[1];
        if (!username || !slug) {
          return $q.reject({'error': 'invalid_path'});
        }
        return libraryByUserSlugService.get(username, slug);
      },
      createLibrary: function (opts) {
        var required = ['name', 'visibility', 'description', 'slug'];
        var missingFields = _.filter(required, function (v) {
          return opts[v] === undefined;
        });
        if (missingFields.length > 0) {
          return $q.reject({'error': 'missing fields: ' + missingFields.join(', ')});
        }
        return $http.post(routeService.createLibrary, opts);
      }
    };

    return api;
  }
]);
