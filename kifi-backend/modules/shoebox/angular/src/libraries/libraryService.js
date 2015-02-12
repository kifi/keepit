'use strict';

angular.module('kifi')

.factory('libraryService', [
  '$http', '$rootScope', 'util', 'profileService', 'routeService', 'Clutch', '$q', 'friendService', '$analytics', '$location',
  function ($http, $rootScope, util, profileService, routeService, Clutch, $q, friendService, $analytics, $location) {
    var librarySummaries = [],
        invitedSummaries = [];

    // Maintain client state for recently kept-to libraries.
    var recentLibraries = [];

    // TODO: flush any non-public cached data when a user logs out.

    //
    // Clutches.
    //
    var userLibrarySummariesService = new Clutch(function () {
      return $http.get(routeService.getLibrarySummaries).then(function (res) {
        var libs = res.data.libraries || [];
        var invites = res.data.invited || [];

        libs.forEach(function(lib) {
          augmentLibrarySummary(lib);
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
          var lib = res.data;
          augmentLibrarySummary(lib);
          return lib;
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
          res.data.library.listed = res.data.listed;
          augmentLibrarySummary(res.data.library);
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

    function augmentLibrarySummary(library) {
      if (library.owner) {
        library.owner.image = friendService.getPictureUrlForUser(library.owner);
        library.isMine = library.owner.id === profileService.me.id;
      }
      if (api.isSystemLibrary(library)) {
        library.color = '#808080';
      }
    }

    function duplicateName(name, oldName) {
      return _.find(librarySummaries, function (librarySummary) {
        return librarySummary.name === name &&
               librarySummary.access === 'owner' &&
               !(oldName && name === oldName);
      });
    }

    function RelatedLibraryDecorator(libData) {
      var library = libData[0], reason = libData[1];

      this.id = library.id;
      this.owner = library.owner;
      this.numFollowers = library.numFollowers;
      this.numKeeps = library.numKeeps;
      this.ownerPicUrl = routeService.formatPicUrl(library.owner.id, library.owner.pictureName, 200);
      this.ownerProfileUrl = routeService.getProfileUrl(library.owner.username);
      this.name = library.name;
      this.description = library.description;
      this.color = library.color;
      this.image = library.image;
      this.imageUrl = library.image ? routeService.libraryImageUrl(library.image.path) : null;
      this.path = '/' + library.owner.username + '/' + library.slug;
      this.reason = reason;
      this.followers = library.followers.map(function (user) {
        return _.merge(user, {
          picUrl: routeService.formatPicUrl(user.id, user.pictureName, 200),
          profileUrl: routeService.getProfileUrl(user.username)
        });
      });
    }


    //
    // API methods.
    //
    var api = {
      librarySummaries: librarySummaries,
      invitedSummaries: invitedSummaries,
      recentLibraries: recentLibraries,

      isSystemLibrary: function (library) {
        return library.kind === 'system_main' || library.kind === 'system_secret';
      },

      isSystemLibraryById: function (libraryId) {
        return _.some(librarySummaries, function (libSum) {
          return (libSum.kind === 'system_main' || libSum.kind === 'system_secret') && libSum.id === libraryId;
        });
      },

      getLibraryNameError: function (name, oldName) {
        function hasInvalidCharacters (myString, invalidChars) {
          return _.some(invalidChars, function (invalidChar) {
            return myString.indexOf(invalidChar) !== -1;
          });
        }

        if (!name.length) {
          return 'Please enter a name for your library';
        } else if (name.length < 3) {
          return 'Please try a longer name';
        } else if (name.length > 50) {
          return 'Please try a shorter name';
        } else if (hasInvalidCharacters(name, ['/', '\\', '"', '\''])) {
          return 'Please no slashes or quotes in your library name';
        } else if (duplicateName(name, oldName)) {
          return 'You already have a library with this name';
        }

        return null;
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
        var lib = _.find(librarySummaries, {id: libraryId});
        return lib.url.match(/[^\/]+\/([^\/]+)/)[1];
      },

      getLibraryInfoById: function (libraryId) {
        return _.find(librarySummaries, {id: libraryId}) || null;
      },

      addToLibraryCount: function (libraryId, val) {
        var lib = _.find(librarySummaries, {id: libraryId});
        lib.numKeeps += val;

        $rootScope.$emit('libraryKeepCountChanged', libraryId, lib.numKeeps);
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
        return $http.post(routeService.joinLibrary(libraryId)).then(function (response) {
          var wasInvited = _.remove(invitedSummaries, {id: libraryId}).length > 0;
          var justJoined = !_.contains(librarySummaries, {id: libraryId});
          if (justJoined) {
            var library = response.data;
            augmentLibrarySummary(library);
            librarySummaries.push(library);
          }
          if (wasInvited || justJoined) {
            $rootScope.$emit('librarySummariesChanged');
          }
          $rootScope.$emit('libraryJoined', libraryId);
        });
      },

      leaveLibrary: function (libraryId) {
        return $http.post(routeService.leaveLibrary(libraryId)).then(function () {
          var wasRemoved = _.remove(librarySummaries, {id: libraryId}).length > 0;
          if (wasRemoved) {
            $rootScope.$emit('librarySummariesChanged');
          }
          $rootScope.$emit('libraryLeft', libraryId);
        });
      },

      declineToJoinLibrary: function (libraryId) {
        return $http.post(routeService.declineToJoinLibrary(libraryId)).then(function () {
          var wasRemoved = _.remove(invitedSummaries, {id: libraryId}).length > 0;
          if (wasRemoved) {
            $rootScope.$emit('librarySummariesChanged');
          }
        });
      },

      deleteLibrary: function (libraryId) {
        return $http.post(routeService.deleteLibrary(libraryId)).then(function () {
          var wasRemoved = _.remove(librarySummaries, {id: libraryId}).length > 0;
          if (wasRemoved) {
            $rootScope.$emit('librarySummariesChanged');
          }
          $rootScope.$emit('libraryDeleted', libraryId);
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

      isMyLibrary: function (library) {
        return library.owner && library.owner.id === profileService.me.id;
      },

      isFollowingLibrary: function (library) {
        return (library.access && (library.access === 'read_only')) ||
               (_.some(librarySummaries, { id: library.id }) && !this.isMyLibrary(library));
      },

      getCommonTrackingAttributes: function (library) {
        var privacySetting = library.visibility === 'secret' ? 'private' : library.visibility;
        var defaultAttributes = {
          type: $rootScope.userLoggedIn ? 'library' : 'libraryLanding',
          followerCount: library.numFollowers,
          followingLibrary: this.isFollowingLibrary(library),
          keepCount: library.numKeeps,
          libraryId: library.id,
          libraryOwnerUserId: library.owner.id,
          libraryOwnerUserName: library.owner.username,
          owner: this.isMyLibrary(library),
          privacySetting: privacySetting,
          hasCoverImage: !!library.image,
          source: 'site'
        };

        if (library.abTest && library.abTestTreatment) {
          defaultAttributes[library.abTest.name] = library.abTestTreatment.name;
        }

        if (library.visibility === 'published') {
          defaultAttributes.libraryName = library.name;
        }

        function setOrigin(str) {
          defaultAttributes.origin = str;
        }

        switch ($location.search().o) {
          case 'rl': setOrigin('libraryRec'); break;
          case 'lac': setOrigin('libraryAttributionChip'); break;
        }

        return defaultAttributes;
      },

      getRelatedLibraries: function (libraryId) {
        var deferred = $q.defer();

        $http.get(routeService.getRelatedLibraries(libraryId)).then(function (resp) {
          var libsWithKind = _.zip(resp.data.libs, resp.data.kinds);
          var decoratedLibs = libsWithKind.map(function (libData) {
            return new RelatedLibraryDecorator(libData);
          });
          deferred.resolve(decoratedLibs);
        });

        return deferred.promise;
      },

      trackEvent: function (eventName, library, attributes) {
        var defaultAttributes = api.getCommonTrackingAttributes(library);
        attributes = _.extend(defaultAttributes, attributes || {});
        $analytics.eventTrack(eventName, attributes);
      }
    };

    return api;
  }
])

;
