'use strict';

angular.module('kifi')

.factory('libraryService', [
  '$http', '$rootScope', 'profileService', 'routeService', 'Clutch', '$q', '$analytics',
  function ($http, $rootScope, profileService, routeService, Clutch, $q, $analytics) {
    var infos = {
      own: []
    };
    var recentIds = [];  // in-memory cache, length limited, most recent first


    // make the membership info INSIDE the library object
    function standarizeMembershipInfo(resp) {
      if (!_.isObject(resp.library.membership)) {
        resp.library.membership = {
          listed: resp.library.listed || resp.listed,
          access: resp.library.access || resp.membership,
          subscribed : resp.library.subscribed || resp.subscribedToUpdates
        };
        if (!resp.library.membership.access || resp.library.membership.access === 'none') {
          resp.library.membership = undefined;
        }
      }
      resp.library.access = undefined;
      resp.access = undefined;
      resp.listed = undefined;
      resp.subscribed = undefined;
      resp.subscribedToUpdates = undefined;
      resp.membership = undefined;
      return resp;
    }

    // TODO: flush any non-public cached data when a user logs out.

    //
    // Clutches.
    //

    var libraryInfosClutch = new Clutch(function () {
      return $http.get(routeService.getLibraryInfos).then(function (res) {
        infos.own = res.data.libraries.map(augment);
      });
    });

    var libraryInfoByIdClutch = new Clutch(function (libraryId) {
      return $http.get(routeService.getLibraryInfoById(libraryId)).then(function (res) {
        return standarizeMembershipInfo(augment(res.data));
      });
    });

    var libraryByIdClutch = new Clutch(function (libraryId) {
      return $http.get(routeService.getLibraryById(libraryId)).then(function (res) {
        return standarizeMembershipInfo(res.data);
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
          res.data = standarizeMembershipInfo(res.data);
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
        return libraryInfosClutch.get();
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

      joinLibrary: function (libraryId, authToken, subscribed) {
        return $http.post(routeService.joinLibrary(libraryId, authToken, subscribed)).then(function (res) {
          $rootScope.$emit('libraryJoined', libraryId, res.data.membership);
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
        return $http.post(routeService.declineToJoinLibrary(libraryId));
      },

      deleteLibrary: function (libraryId) {
        return $http.post(routeService.deleteLibrary(libraryId)).then(function () {
          _.remove(infos.own, {id: libraryId});
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
