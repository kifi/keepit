'use strict';

angular.module('kifi')

.constant('LIB_PERMISSION', {
  INVITE_COLLABORATORS: 'invite_collaborators',
  MOVE_LIBRARY: 'move_library',
  INVITE_FOLLOWERS: 'invite_followers',
  VIEW_LIBRARY: 'view_library',
  DELETE_LIBRARY: 'delete_library',
  REMOVE_OWN_KEEPS: 'remove_own_keeps',
  REMOVE_OTHER_KEEPS: 'remove_other_keeps',
  CREATE_SLACK_INTEGRATION: 'create_slack_integration',
  EDIT_LIBRARY: 'edit_library',
  EDIT_OWN_KEEPS: 'edit_own_keeps',
  REMOVE_MEMBERS: 'remove_members',
  ADD_KEEPS: 'add_keeps'
})

.factory('libraryService', [
  '$http', '$rootScope', 'profileService', 'routeService', '$q', '$analytics', 'net',
  function ($http, $rootScope, profileService, routeService, $q, $analytics, net) {
    var infos;
    var libraryIdsRecentlyKeptTo = [];  // in-memory cache, length limited, most recent first

    // TODO: flush any non-public cached data when a user logs out.

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
      this.path = library.path || ('/' + library.owner.username + '/' + library.slug);
      this.reason = reason;
      this.followers = library.followers;
    }


    //
    // API methods.
    //
    var api = {
      getOwnInfos: function () {
        return (infos || []).slice();
      },

      getSysMainInfo: function () {
        return _.find(infos, {kind: 'system_main'});
      },

      getSysSecretInfo: function () {
        return _.find(infos, {kind: 'system_secret'});
      },

      isLibraryMainOrSecret: function (library) {
        return library.kind === 'system_main' || library.kind === 'system_secret';
      },

      isLibraryIdMainOrSecret: function (libraryId) {
        var info = _.find(infos, {id: libraryId});
        return info && api.isLibraryMainOrSecret(info);
      },

      getLibraryNameError: function (name, oldName) {
        if (!name) {
          return 'Please enter a name for your library';
        } else if (name.length < 3) {
          return 'Please try a longer name';
        } else if (name.length > 50) {
          return 'Please try a shorter name';
        } else if (/[\/]/.test(name)) {
          return 'Please no slashes in your library name';
        } else if (oldName && name !== oldName && _.some(infos, {name: name})) {
          return 'You already have a library with this name';
        } else {
          return null;
        }
      },

      fetchLibraryInfos: function (invalidateCache) {
        if (invalidateCache) {
          net.getKeepableLibraries.clearCache();
        }
        return net.getKeepableLibraries().then(function (res) {
          infos = res.data.libraries.map(augment);
          return infos;
        });
      },

      getLibraryById: function (libraryId, invalidateCache) {
        if (invalidateCache) {
          net.getLibraryById.clearCache();
        }
        return net.getLibraryById(libraryId).then(function (res) {
          return res.data;
        });
      },

      getLibraryInfoById: function (libraryId) {
        return net.getLibraryInfoById(libraryId).then(function (res) {
          return augment(res.data);
        });
      },

      getLibraryByHandleAndSlug: function (handle, slug, authToken, invalidateCache) {
        if (invalidateCache) {
          net.getLibraryByHandleAndSlug.clearCache();
        }
        var libPromise = net.getLibraryByHandleAndSlug(handle, slug, authToken).then(function (res) {
          res.data.library.suggestedSearches = (res.data.suggestedSearches && res.data.suggestedSearches.terms) || [];
          return augment(res.data.library);
        });

        libPromise['catch'](function (e) {
          if (e.status === 403) {
            var eventName;
            if (profileService.me.id) {
              eventName = 'user_viewed_page';
            } else {
              eventName = 'visitor_viewed_page';
            }
            $analytics.eventTrack(eventName, {'type': 'libraryWhomp'});
          }
        });

        return libPromise;
      },

      getKeepsInLibrary: function (libraryId, offset, authToken) {
        return net.getKeepsInLibrary(libraryId, {count: 10, offset: offset, authToken: authToken || []}).then(function (res) {
          return res.data;
        });
      },

      expireKeepsInLibraries: function () {
        net.getKeepsInLibrary.clearCache();
        net.getLibraryByHandleAndSlug.clearCache();  // contains keeps too
      },

      addToLibraryCount: function (libraryId, val) {
        var lib = _.find(infos, { id: libraryId });
        if (lib) {
          lib.numKeeps += val;
        }
        $rootScope.$emit('libraryKeepCountChanged', libraryId, (lib && lib.numKeeps) || val); // Best effort?
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

        return net.createLibrary(opts)
        .then(function (res) { $rootScope.$emit('libraryCreated', res); return res;});
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
        return net.modifyLibrary(opts.id, opts)
        .then(function (res) { $rootScope.$emit('libraryModified', opts.id, res); return res;});
      },

      getLibraryShareContacts: function (libId, query) {
        return net.getLibraryShareSuggest(libId, {q: query || []}).then(function (res) {
          return res.data.members;
        });
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

      joinLibrary: function (libraryId, authToken, subscribed) {
        return $http.post(routeService.joinLibrary(libraryId, authToken, subscribed)).then(function (res) {
          $rootScope.$emit('libraryJoined', libraryId, res.data.membership);
          net.getLibraryInfoById.clearCache();
          return res.data.membership;
        });
      },

      leaveLibrary: function (libraryId) {
        return $http.post(routeService.leaveLibrary(libraryId)).then(function () {
          $rootScope.$emit('libraryLeft', libraryId);
          net.getLibraryInfoById.clearCache();
        });
      },

      declineToJoinLibrary: function (libraryId) {
        return $http.post(routeService.declineToJoinLibrary(libraryId));
      },

      deleteLibrary: function (libraryId) {
        return $http.post(routeService.deleteLibrary(libraryId)).then(function () {
          _.remove(infos, {id: libraryId});
          $rootScope.$emit('libraryDeleted', libraryId);
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
        net.getLibraryInfoById.clearCache();
        return $http.post(routeService.updateSubscriptionToLibrary(libraryId, subscribed)).then(function(resp) {
          return resp.data;
        });
      },

      noteLibraryViewed: function (libraryId) {
        (_.find(infos, {id: libraryId}) || {}).lastViewed = Date.now();
      },

      noteLibraryKeptTo: function (libraryId) {
        _.remove(libraryIdsRecentlyKeptTo, libraryId);
        libraryIdsRecentlyKeptTo.unshift(libraryId);
        if (libraryIdsRecentlyKeptTo.length > 3) {
          libraryIdsRecentlyKeptTo.length = 3;
        }
        (_.find(infos, {id: libraryId}) || {}).lastKept = Date.now();
      },

      getRecentIds: function () {
        return libraryIdsRecentlyKeptTo.slice();
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
          source: 'site',
          orgId: library.org ? library.org.id : ''
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

      getFtueLibraries: function () {
        return net.getFtueLibraries();
      },

      joinLibraries: function(libraryIds) {
        return net.joinLibraries(libraryIds);
      },

      // integrationsToDelete => ["integration-id", ...]
      deleteSlackIntegrations: function(libraryId, integrationsToDelete) {
        return net.deleteLibrarySlackIntegrations(libraryId, {'integrations': integrationsToDelete});
      },

      trackEvent: function (eventName, library, attributes) {
        var defaultAttributes = api.getCommonTrackingAttributes(library);
        attributes = _.extend(defaultAttributes, attributes || {});
        $analytics.eventTrack(eventName, attributes);
      },

      checkLibraryForUpdates: function (libraryId, since) {
        return net.checkLibraryForUpdates(libraryId, since);
      }
    };

    return api;
  }
])

;
