'use strict';

angular.module('kifi')

.factory('libraryService', [
  '$http', '$rootScope', 'util', 'profileService', 'routeService', 'Clutch', '$q', 'friendService', '$analytics',
  function ($http, $rootScope, util, profileService, routeService, Clutch, $q, friendService, $analytics) {
    var librarySummaries = [],
        invitedSummaries = [];

    // Maintain client state for recently kept-to libraries.
    var recentLibraries = [];


    //
    // Clutches.
    //
    var userLibrarySummariesService = new Clutch(function () {
      return $http.get(routeService.getLibrarySummaries).then(function (res) {
        var libs = res.data.libraries || [];
        var invites = res.data.invited || [];

        libs.forEach(function(lib) {
          augmentLibrarySummary(lib);
          lib.isMine = lib.owner.id === profileService.me.id;
        });

        invites.forEach(function(lib) {
          augmentLibrarySummary(lib);
        });

        util.replaceArrayInPlace(librarySummaries, libs);
        util.replaceArrayInPlace(invitedSummaries, invites);

        return res.data;
      });
    });

    var librarySummaryService = new Clutch(function (libraryId) {
      var mayBeLib = _.find(librarySummaries, function (l) {
        return l.id === libraryId;
      });
      if (mayBeLib) {
        return $q.when({
          library: mayBeLib,
          membership: mayBeLib.access
        });
      } else {
        return $http.get(routeService.getLibrarySummaryById(libraryId)).then(function (res) {
          return res.data;
        });
      }
    });

    var libraryByIdService = new Clutch(function (libraryId) {
      return $http.get(routeService.getLibraryById(libraryId)).then(function (res) {
        return res.data;
      });
    });

    var libraryByUserSlugService = new Clutch(function (username, slug, authToken) {
      return $http.get(routeService.getLibraryByUserSlug(username, slug, authToken)).then(function (res) {
        // TODO: Take this manual check out when the backend endpoint properly has an 'access' property
        //       on the library object.
        if (res.data && res.data.library) {
          res.data.library.access = res.data.membership;
          return res.data.library;
        }
        return null;
      });
    });

    var keepsInLibraryService = new Clutch(function (libraryId, count, offset, authToken) {
      return $http.get(routeService.getKeepsInLibrary(libraryId, count, offset, authToken)).then(function (res) {
        return res.data;
      });
    });

    // TODO(yiping): figure out whether this service belongs so specifically within libraryService.
    var contactSearchService = new Clutch(function (libId, opt_query) {
      return $http.get(routeService.libraryShareSuggest(libId, opt_query)).then(function (res) {
        return res.data.members;
      });
    });


    //
    // Internal helper methods.
    //
    function shortenLibName(fullName) {
      var maxLength = 25;
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

    function augmentLibrarySummary(library) {
      if (library.owner) {
        library.owner.image = friendService.getPictureUrlForUser(library.owner);
      }
      var lines = shortenLibName(library.name);
      library.firstLine = lines[0];
      library.secondLine = lines[1];
    }


    //
    // API methods.
    //
    var api = {
      librarySummaries: librarySummaries,
      invitedSummaries: invitedSummaries,
      recentLibraries: recentLibraries,

      isAllowed: function () {
        return profileService.me.experiments && profileService.me.experiments.indexOf('libraries') !== -1;
      },

      isSystemLibrary: function (libraryId) {
        return _.some(librarySummaries, function (libSum) {
          return (libSum.kind === 'system_main' || libSum.kind === 'system_secret') && libSum.id === libraryId;
        });
      },

      fetchLibrarySummaries: function (invalidateCache) {
        if (invalidateCache) {
          userLibrarySummariesService.expire();
        }
        return userLibrarySummariesService.get().then(function (response) {
          $rootScope.$emit('librarySummariesChanged');
          return response;
        });
      },

      getLibraryById: function (libraryId, invalidateCache) {
        if (invalidateCache) {
          libraryByIdService.expire(libraryId);
        }
        return libraryByIdService.get(libraryId);
      },

      getLibrarySummaryById: function (libraryId, invalidateCache) {
        if (invalidateCache) {
          librarySummaryService.expire(libraryId);
        }
        return librarySummaryService.get(libraryId);
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

      // TODO(yiping): All functions that update library summaries should refetch automatically instead of
      // having client refetch.
      createLibrary: function (opts) {
        var required = ['name', 'visibility', 'slug'];
        var missingFields = _.filter(required, function (v) {
          return opts[v] === undefined;
        });

        if (missingFields.length > 0) {
          return $q.reject({'error': 'missing fields: ' + missingFields.join(', ')});
        }
        return $http.post(routeService.createLibrary, opts);
      },

      modifyLibrary: function (opts) {
        var required = ['name', 'visibility', 'slug'];
        var missingFields = _.filter(required, function (v) {
          return opts[v] === undefined;
        });

        if (missingFields.length > 0) {
          return $q.reject({'error': 'missing fields: ' + missingFields.join(', ')});
        }
        return $http.post(routeService.modifyLibrary(opts.id), opts);
      },

      getLibraryShareContacts: function (libId, opt_query) {
        return contactSearchService.get(libId, opt_query || '');
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
            var library = response.data;
            augmentLibrarySummary(library);

            librarySummaries.push(library);
            _.remove(invitedSummaries, { id: libraryId });

            $rootScope.$emit('librarySummariesChanged');
            $rootScope.$emit('libraryUpdated', library);
          });
        }

        return $q.when('already_joined');
      },

      leaveLibrary: function (libraryId) {
        return $http.post(routeService.leaveLibrary(libraryId)).then(function () {
          _.remove(librarySummaries, { id: libraryId });
          $rootScope.$emit('librarySummariesChanged');

          return api.getLibraryById(libraryId, true).then(function (data) {
            // TODO: Take this manual check out when the backend endpoint properly has an 'access' property
            //       on the library object.
            data.library.access = data.membership;
            $rootScope.$emit('libraryUpdated', data.library);
          });
        });
      },

      declineToJoinLibrary: function (libraryId) {
        return $http.post(routeService.declineToJoinLibrary(libraryId)).then(function () {
          _.remove(invitedSummaries, { id: libraryId });
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

      authIntoLibrary: function (username, slug, authToken, passPhrase) {
        return $http.post(routeService.authIntoLibrary(username, slug, authToken), {'passPhrase': passPhrase}).then(function (resp) {
          return resp;
        });
      },

      copyKeepsFromTagToLibrary: function (libraryId, tagName) {
        return $http.post(routeService.copyKeepsFromTagToLibrary(libraryId, tagName)).then(function(resp) {
          return resp.data;
        });
      },

      moveKeepsFromTagToLibrary: function (libraryId, tagName) {
        return $http.post(routeService.moveKeepsFromTagToLibrary(libraryId, tagName)).then(function(resp) {
          return resp.data;
        });
      },

      getMoreMembers: function (libraryId, pageSize, offset) {
        return $http.get(routeService.getMoreLibraryMembers(libraryId, pageSize, offset)).then(function(resp) {
          return resp.data;
        });
      },

      addRecentLibrary: function (libraryId) {
        // Maintain a short queue of 3 itmes to store recent libraries.
        recentLibraries.push(libraryId);
        if (recentLibraries.length > 3) {
          recentLibraries.shift();
        }
      },

      getCommonTrackingAttributes: function (library) {
        var defaultAttributes = {
          libraryId: library.id,
          libraryOwnerUserId: library.owner.id,
          libraryOwnerUserName: library.owner.username,
          followerCount: library.numFollowers,
          keepCount: library.numKeeps,
          privacySetting: library.visibility,
          source: 'site'
        };

        if (library.visibility === 'published') {
          defaultAttributes.libraryName = library.name;
        }

        return defaultAttributes;
      },

      trackEvent: function (eventName, library, attributes) {
        var defaultAttributes = api.getCommonTrackingAttributes(library);
        attributes = _.extend(defaultAttributes, attributes || {});
        $analytics.eventTrack(eventName, attributes);
      }
    };

    return api;
  }
]);
