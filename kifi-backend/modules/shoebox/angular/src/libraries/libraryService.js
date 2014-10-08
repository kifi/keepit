'use strict';

angular.module('kifi')

.factory('libraryService', [
  '$http', '$rootScope', 'util', 'profileService', 'routeService', 'Clutch', '$q', 'friendService',
  function ($http, $rootScope, util, profileService, routeService, Clutch, $q, friendService) {
    var librarySummaries = [],
        invitedSummaries = [];

    var librarySummariesService = new Clutch(function () {
      return $http.get(routeService.getLibrarySummaries).then(function (res) {
        var libs = res.data.libraries || [];
        var invites = res.data.invited || [];

        var lines;
        libs.forEach(function(lib) {
          lines = shortenLibName(lib.name);
          lib.firstLine = lines[0];
          lib.secondLine = lines[1];
          if (lib.owner) {
            lib.owner.image = friendService.getPictureUrlForUser(lib.owner);
          }
          lib.isMine = lib.owner.id === profileService.me.id;
        });

        invites.forEach(function(lib) {
          lines = shortenLibName(lib.name);
          lib.firstLine = lines[0];
          lib.secondLine = lines[1];
          if (lib.owner) {
            lib.owner.image = friendService.getPictureUrlForUser(lib.owner);
          }
        });
        util.replaceArrayInPlace(librarySummaries, libs);
        util.replaceArrayInPlace(invitedSummaries, invites);
        return res.data;
      });
    });

    var libraryByIdService = new Clutch(function (libraryId) {
      return $http.get(routeService.getLibraryById(libraryId)).then(function (res) {
        return res.data;
      });
    });

    var libraryByUserSlugService = new Clutch(function (username, slug, authToken) {
      return $http.get(routeService.getLibraryByUserSlug(username, slug, authToken)).then(function (res) {
        return res.data && res.data.library;
      });
    });

    var keepsInLibraryService = new Clutch(function (libraryId, count, offset, authToken) {
      return $http.get(routeService.getKeepsInLibrary(libraryId, count, offset, authToken)).then(function (res) {
        return res.data;
      });
    });

    // TODO(yiping): figure out whether this service belongs so specifically within libraryService.
    var contactSearchService = new Clutch(function (opt_query) {
      return $http.get(routeService.contactSearch(opt_query)).then(function (res) {
        return res.data;
      });
    });

    var maxLength = 25;

    function shortenLibName(fullName) {
      var firstLine = fullName;
      var secondLine = '';
      if (fullName.length > maxLength) {
        var full = false;
        var line = '';
        while (!full) {
          var pos = fullName.indexOf(' ');
          if (pos >= 0 && line.length + pos <= maxLength) {
            line = line + fullName.substr(0, pos+1);
            fullName = fullName.slice(pos+1);
          } else {
            full = true;
          }
        }
        firstLine = line;
        var remainingLen = fullName.length;
        if (remainingLen > 0) {
          if (remainingLen < maxLength) {
            secondLine = fullName.substr(0, remainingLen);
          } else {
            secondLine = fullName.substr(0, maxLength-3) + '...';
          }
        }
      }
      return [firstLine, secondLine];
    }

    var api = {
      librarySummaries: librarySummaries,
      invitedSummaries: invitedSummaries,

      isAllowed: function () {
        return profileService.me.experiments && profileService.me.experiments.indexOf('libraries') !== -1;
      },

      fetchLibrarySummaries: function (invalidateCache) {
        if (invalidateCache) {
          librarySummariesService.expire();
        }
        return librarySummariesService.get();
      },

      getLibraryById: function (libraryId, invalidateCache) {
        if (invalidateCache) {
          libraryByIdService.expire(libraryId);
        }
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

      getLibraryByUserSlug: function (username, slug, authToken, invalidateCache) {
        if (invalidateCache) {
          libraryByUserSlugService.expire(username, slug, authToken);
        }
        return libraryByUserSlugService.get(username, slug, authToken);
      },

      getKeepsInLibrary: function (libraryId, offset, authToken) {
        return keepsInLibraryService.get(libraryId, 10, offset, authToken);
      },

      getSlugById: function (libraryId) {
        var lib = _.find(librarySummaries, function (librarySummary) {
          return librarySummary.id === libraryId;
        });

        if (!!lib) {
          var split = lib.url.split('/').filter(function (a) { return a.length !== 0; });
          return split[1];
        }

        return null;
      },

      getLibraryInfoById: function (libraryId) {
        var lib = _.find(librarySummaries, function (librarySummary) {
          return librarySummary.id === libraryId;
        });

        return lib || null;
      },

      addToLibraryCount: function (libraryId, val) {
        var lib = _.find(librarySummaries, function (librarySummary) {
          return librarySummary.id === libraryId;
        });
        lib.numKeeps += val;

        $rootScope.$emit('libraryUpdated', lib);
        $rootScope.$emit('librarySummariesChanged');
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
      },

      modifyLibrary: function (opts) {
        var required = ['name', 'visibility', 'description', 'slug'];
        var missingFields = _.filter(required, function (v) {
          return opts[v] === undefined;
        });

        if (missingFields.length > 0) {
          return $q.reject({'error': 'missing fields: ' + missingFields.join(', ')});
        }
        return $http.post(routeService.modifyLibrary(opts.id), opts);
      },

      getLibraryShareContacts: function (opt_query) {
        return contactSearchService.get(opt_query);
      },

      shareLibrary: function (libraryId, opts) {
        var required = ['invites'];
        var missingFields = _.filter(required, function (v) {
          return opts[v] === undefined;
        });

        if (missingFields.length > 0) {
          return $q.reject({'error': 'missing fields: ' + missingFields.join(', ')});
        }
        return $http.post(routeService.shareLibrary(libraryId), opts);
      },

      joinLibrary: function (libraryId) {
        var alreadyJoined = _.some(librarySummaries, { id: libraryId });

        if (!alreadyJoined) {
          return $http.post(routeService.joinLibrary(libraryId)).then(function (response) {
            librarySummaries.push(response.data);
            _.remove(invitedSummaries, { id: libraryId });
            $rootScope.$emit('librarySummariesChanged');
          });
        }

        return $q.when('already_joined');
      },

      leaveLibrary: function (libraryId) {
        return $http.post(routeService.leaveLibrary(libraryId)).then(function () {
          _.remove(librarySummaries, { id: libraryId });
          $rootScope.$emit('librarySummariesChanged');
        });
      },

      deleteLibrary: function (libraryId) {
        return $http.post(routeService.deleteLibrary(libraryId)).then(function () {
          _.remove(librarySummaries, function (library) {
            return library.id === libraryId;
          });
        });
      },

      authIntoLibrary: function (libraryId, authToken, passPhrase) {
        return $http.post(routeService.authIntoLibrary(libraryId), {'passPhrase': passPhrase}).then(function (resp) {
          return resp;
        });
      }
    };

    return api;
  }
]);
