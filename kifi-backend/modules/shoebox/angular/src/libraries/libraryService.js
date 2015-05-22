'use strict';

angular.module('kifi')

.factory('libraryService', [
  '$http', '$rootScope', 'profileService', 'routeService', 'Clutch', '$q', '$analytics',
  function ($http, $rootScope, profileService, routeService, Clutch, $q, $analytics) {
    var infos = {
      own: [],
      invited: []
    };
    var recentIds = [];  // in-memory cache, length limited, most recent first

    // TODO: flush any non-public cached data when a user logs out.

    //
    // Clutches.
    //

    var libraryInfosClutch = new Clutch(function () {
      return $http.get(routeService.getLibraryInfos).then(function (res) {
        infos.own = res.data.libraries.map(augment);
        infos.invited = res.data.invited.map(augment);
      });
    });

    var libraryInfoByIdClutch = new Clutch(function (libraryId) {
      return $http.get(routeService.getLibraryInfoById(libraryId)).then(function (res) {
        return augment(res.data);
      });
    });

    var libraryByIdClutch = new Clutch(function (libraryId) {
      return $http.get(routeService.getLibraryById(libraryId)).then(function (res) {
        return res.data;
      });
    });

    var libraryByUserSlugClutch = new Clutch(function (username, slug, authToken) {
      return $http.get(routeService.getLibraryByUserSlug(username, slug, authToken)).then(function (res) {
        if (res.data && res.data.library) {
          // do we really need to be moving these into the library object?
          res.data.library.access = res.data.membership;
          res.data.library.listed = res.data.listed;
          res.data.library.suggestedSearches = (res.data.suggestedSearches && res.data.suggestedSearches.terms) || [];
          res.data.library.subscribed = res.data.subscribedToUpdates;
          return augment(res.data.library);
        }
        return null;
      });
    });

    var keepsInLibraryClutch = new Clutch(function (libraryId, count, offset, authToken) {
      return $http.get(routeService.getKeepsInLibrary(libraryId, count, offset, authToken)).then(function (res) {
        return res.data;
      });
    });

    // TODO(yiping): figure out whether this service belongs so specifically within libraryService.
    var contactSearchClutch = new Clutch(function (libId, opt_query) {
      return $http.get(routeService.libraryShareSuggest(libId, opt_query)).then(function (res) {
        return res.data.members;
      });
    });


    //
    // Internal helper methods.
    //

    function augment(library) {  // used on both library infos and full libraries
      if (api.isLibraryMainOrSecret(library)) {
        library.color = '#808080';
      }
      return library;
    }

    function RelatedLibraryDecorator(libData) {
      var library = libData[0], reason = libData[1];

      this.id = library.id;
      this.owner = library.owner;
      this.numFollowers = library.numFollowers;
      this.numKeeps = library.numKeeps;
      this.name = library.name;
      this.description = library.description;
      this.color = library.color;
      this.image = library.image;
      this.path = '/' + library.owner.username + '/' + library.slug;
      this.reason = reason;
      this.followers = library.followers;
    }


    //
    // API methods.
    //
    var api = {
      getOwnInfos: function () {
        return infos.own.slice();
      },

      getInvitedInfos: function () {
        return infos.invited.slice();
      },

      getSysMainInfo: function () {
        return _.find(infos.own, {kind: 'system_main'});
      },

      getSysSecretInfo: function () {
        return _.find(infos.own, {kind: 'system_secret'});
      },

      isLibraryMainOrSecret: function (library) {
        return library.kind === 'system_main' || library.kind === 'system_secret';
      },

      isLibraryIdMainOrSecret: function (libraryId) {
        var info = _.find(infos.own, {id: libraryId});
        return info && api.isLibraryMainOrSecret(info);
      },

      getLibraryNameError: function (name, oldName) {
        if (!name) {
          return 'Please enter a name for your library';
        } else if (name.length < 3) {
          return 'Please try a longer name';
        } else if (name.length > 50) {
          return 'Please try a shorter name';
        } else if (/['"\/\\]/.test(name)) {
          return 'Please no slashes or quotes in your library name';
        } else if (oldName && name !== oldName && _.some(infos.own, {name: name})) {
          return 'You already have a library with this name';
        } else {
          return null;
        }
      },

      fetchLibraryInfos: function (invalidateCache) {
        if (invalidateCache) {
          libraryInfosClutch.expire();
        }
        return libraryInfosClutch.get().then(function () {
          $rootScope.$emit('libraryInfosChanged');
        });
      },

      getLibraryById: function (libraryId, invalidateCache) {
        if (invalidateCache) {
          libraryByIdClutch.expire(libraryId);
        }
        return libraryByIdClutch.get(libraryId);
      },

      getLibraryInfoById: function (libraryId) {
        return libraryInfoByIdClutch.get(libraryId);
      },

      getLibraryByUserSlug: function (username, slug, authToken, invalidateCache) {
        if (invalidateCache) {
          libraryByUserSlugClutch.expire(username, slug, authToken);
        }
        return libraryByUserSlugClutch.get(username, slug, authToken);
      },

      getKeepsInLibrary: function (libraryId, offset, authToken) {
        return keepsInLibraryClutch.get(libraryId, 10, offset, authToken);
      },

      expireKeepsInLibraries: function () {
        keepsInLibraryClutch.expireAll();
        libraryByUserSlugClutch.expireAll();  // contains keeps too
      },

      addToLibraryCount: function (libraryId, val) {
        var lib = _.find(infos.own, {id: libraryId});
        lib.numKeeps += val;

        $rootScope.$emit('libraryKeepCountChanged', libraryId, lib.numKeeps);
        $rootScope.$emit('libraryInfosChanged');
      },

      // TODO(yiping): All functions that update library infos should refetch automatically instead of
      // having client refetch.
      createLibrary: function (opts, checkMissingFields) {
        if (checkMissingFields) {
          var missingFields = _.filter(['name', 'visibility', 'slug'], function (v) {
            return !opts[v];
          });

          if (missingFields.length > 0) {
            return $q.reject({'error': 'missing fields: ' + missingFields.join(', ')});
          }
        }
        return $http.post(routeService.createLibrary, opts);
      },

      modifyLibrary: function (opts, checkMissingFields) {
        if (checkMissingFields) {
          var required = ['name', 'visibility', 'slug'];
          var missingFields = _.filter(required, function (v) {
            return opts[v] === undefined;
          });
          if (missingFields.length > 0) {
            return $q.reject({'error': 'missing fields: ' + missingFields.join(', ')});
          }
        }
        return $http.post(routeService.modifyLibrary(opts.id), opts);
      },

      getLibraryShareContacts: function (libId, opt_query) {
        return contactSearchClutch.get(libId, opt_query || '');
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

      joinLibrary: function (libraryId, authToken) {
        return $http.post(routeService.joinLibrary(libraryId, authToken)).then(function (response) {
          var wasInvited = _.remove(infos.invited, {id: libraryId}).length > 0;
          if (wasInvited) {
            $rootScope.$emit('libraryInfosChanged');
          }
          $rootScope.$emit('libraryJoined', libraryId);
          libraryInfoByIdClutch.expire(libraryId);
        });
      },

      leaveLibrary: function (libraryId) {
        return $http.post(routeService.leaveLibrary(libraryId)).then(function () {
          $rootScope.$emit('libraryLeft', libraryId);
          libraryInfoByIdClutch.expire(libraryId);
        });
      },

      declineToJoinLibrary: function (libraryId) {
        return $http.post(routeService.declineToJoinLibrary(libraryId)).then(function () {
          var wasRemoved = _.remove(infos.invited, {id: libraryId}).length > 0;
          if (wasRemoved) {
            $rootScope.$emit('libraryInfosChanged');
          }
        });
      },

      deleteLibrary: function (libraryId) {
        return $http.post(routeService.deleteLibrary(libraryId)).then(function () {
          var wasRemoved = _.remove(infos.own, {id: libraryId}).length > 0;
          if (wasRemoved) {
            $rootScope.$emit('libraryInfosChanged');
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

      updateSubscriptionToLibrary: function(libraryId, subscribed) {
        return $http.post(routeService.updateSubscriptionToLibrary(libraryId, subscribed)).then(function(resp) {
          return resp.data;
        });
      },

      rememberRecentId: function (libraryId) {
        _.remove(recentIds, libraryId);
        recentIds.unshift(libraryId);
        if (recentIds.length > 3) {
          recentIds.pop();
        }
      },

      getRecentIds: function () {
        return recentIds.slice();
      },

      isMyLibrary: function (library) {
        return library.owner && library.owner.id === profileService.me.id;
      },

      getCommonTrackingAttributes: function (library) {
        var privacySetting = library.visibility === 'secret' ? 'private' : library.visibility;
        var defaultAttributes = {
          type: $rootScope.userLoggedIn ? 'library' : 'libraryLanding',
          followerCount: library.numFollowers,
          followingLibrary: library.access === 'read_only',
          keepCount: library.numKeeps,
          libraryId: library.id,
          libraryOwnerUserId: library.owner.id,
          libraryOwnerUserName: library.owner.username,
          owner: api.isMyLibrary(library),
          privacySetting: privacySetting,
          hasCoverImage: !!library.image,
          source: 'site'
        };

        if (library.visibility === 'published') {
          defaultAttributes.libraryName = library.name;
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
