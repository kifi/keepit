'use strict';

angular.module('kifi')

// DEPRECATED. Use the 'net' service instead.

.factory('routeService', [
  'env', 'URI',
  function (env, URI) {
    function route(path, params) {
      return env.xhrBase + path + (params ? URI.formatQueryString(params) : '');
    }

    function navRoute(path, params) {
      return env.navBase + path + (params ? URI.formatQueryString(params) : '');
    }

    return {
      disconnectNetwork: function (network) {
        return env.navBase + '/disconnect/' + network;
      },
      linkNetwork: function (network) {
        return env.navBase + '/link/' + network;
      },
      uploadBookmarkFileToLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/import-file');
      },
      refreshNetworks: env.navBase + '/friends/invite/refresh', // would love to be more ajax-y
      importStatus: route('/user/import-status'),
      prefs: route('/user/prefs'),
      importGmail: function (returnPath) {
        return navRoute('/contacts/import', {redirectUrl: returnPath || []});
      },
      networks: route('/user/networks'),
      profileUrl: route('/user/me'),
      profileSettings: route('/user/settings'),
      emailInfoUrl: route('/user/email'),
      abooksUrl: route('/user/abooks'),
      resendVerificationUrl: route('/user/resend-verification'),
      userPasswordUrl: route('/user/password'),
      userBiography: route('/user/me/biography'),
      formatPicUrl: function (userId, pictureName, size) {
        return env.picBase + '/users/' + userId + '/pics/' + (size || 200) + '/' + pictureName;
      },

      ////////////////////////////
      // Tags                   //
      ////////////////////////////
      pageTags: function (sort, offset, pageSize) {
        return route('/collections/page', {sort: sort, offset: offset, pageSize: pageSize});
      },
      searchTags: function (query, limit) {
        return route('/collections/search', {query: query, limit: limit});
      },
      suggestTags: function (libraryId, keepId, query) {
        return route('/libraries/' + libraryId + '/keeps/' + keepId + '/tags/suggest', {q: query});
      },
      deleteTag: function (tagId) {
        return route('/collections/' + tagId + '/delete');
      },
      undeleteTag: function (tagId) {
        return route('/collections/' + tagId + '/undelete');
      },

      whoToInvite: route('/user/invite/recommended'),
      blockWtiConnection: route('/user/invite/hide'),
      friendRequest: function (id) {
        return env.xhrBase + '/user/' + id + '/friend';
      },
      socialInvite: '/invite',
      connectTwitter: '/twitter/request',
      invite: route('/user/invite'),
      peopleYouMayKnow: function (offset, limit) {
        return route('/user/friends/recommended', {offset: offset, limit: limit});
      },
      hideUserRecommendation: function (id) {
        return route('/user/' + id + '/hide');
      },
      socialSearch: function (name, limit) {
        return route('/user/connections/all/search', {
          query: name,
          limit: limit || 6,
          pictureUrl: 'true'
        });
      },
      exportKeeps: route('/keeps/export'),
      postDelightedAnswer: route('/user/delighted/answer'),
      cancelDelightedSurvey: route('/user/delighted/cancel'),
      userCloseAccount: route('/user/close'),
      adHocRecos: function (howMany) {
        return route('/recos/adHoc', {n: howMany});
      },
      recos: function (opts) {
        return route('/recos/topV2', {
          recency: opts.recency,
          uriContext: opts.uriContext || [],
          libContext: opts.libContext || [],
          trackLibDelivery: _.isBoolean(opts.trackLibDelivery) ? opts.trackLibDelivery : []
        });
      },
      recosPublic: function () {
        return route('/recos/public');
      },
      recoFeedback: function (urlId) {
        return route('/recos/feedback', {id: urlId});
      },
      libraryRecos: function () {
        return route('/libraries/recos/top');
      },
      libraryRecoFeedback: function (libraryId) {
        return route('/libraries/recos/feedback', {id: libraryId});
      },
      basicUserInfo: function (id, friendCount) {
        return route('/user/' + id, {friendCount: friendCount ? 1 : []});
      },
      saveKeepNote: function (libraryId, keepId) {
        return route('/libraries/' + libraryId + '/keeps/' + keepId + '/note');
      },

      ////////////////////////////
      // User registration      //
      ////////////////////////////
      socialSignup: function (provider, opts) {
        var params = {
          publicLibraryId : opts.libraryId || [],
          intent: opts.intent || [],
          libAuthToken: opts.libAuthToken || []
        };
        return navRoute('/signup/' + provider, params);
      },

      socialSignupWithToken: function (provider) {
        return navRoute('/auth/token-signup/' + provider);
      },

      socialFinalize: env.navBase + '/auth/token-finalize',
      emailSignup: env.navBase + '/auth/email-signup',

      ////////////////////////////
      // Libraries              //
      ////////////////////////////
      createLibrary: route('/libraries/add'),
      modifyLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/modify');
      },
      shareLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/invite');
      },
      joinLibrary: function (libraryId, authToken, subscribed) {
        return route('/libraries/' + libraryId + '/join', {authToken: authToken || [], subscribed: subscribed != null ? subscribed ? 1 : 0 : []});
      },
      leaveLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/leave');
      },
      declineToJoinLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/decline');
      },
      deleteLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/delete');
      },
      updateSubscriptionToLibrary: function (libraryId, subscribed) {
        return route('/libraries/' + libraryId + '/subscription', {subscribed: subscribed ? 1 : 0});
      },
      uploadLibraryCoverImage: function (libraryId, x, y, idealSize) {
        return route('/libraries/' + libraryId + '/image/upload', {x: x, y: y, is: idealSize || []});
      },
      positionLibraryCoverImage: function (libraryId) {
        return route('/libraries/' + libraryId + '/image/position');
      },
      removeLibraryCoverImage: function (libraryId) {
        return route('/libraries/' + libraryId + '/image');
      },
      getLibraryCoverImages: function (libraryIds, w, h) {
        return route('/libraries/' + libraryIds.join('.') + '/images', {is: w && h ? w + 'x' + h : []});
      },
      authIntoLibrary: function (handle, slug, authToken) {
        return route('/users/' + handle + '/libraries/' + slug + '/auth', {authToken: authToken || []});
      },
      copyKeepsFromTagToLibrary: function(libraryId, tagName) {
        return route('/libraries/' + libraryId + '/importTag', {tag: tagName});
      },
      moveKeepsFromTagToLibrary: function(libraryId, tagName) {
        return route('/libraries/' + libraryId + '/moveTag', {tag: tagName});
      },
      getMoreLibraryMembers: function(libraryId, pageSize, offset) {
        return route('/libraries/' + libraryId + '/members', {limit: pageSize, offset: offset * pageSize});
      },
      getRelatedLibraries: function (libraryId) {
        return route('/libraries/' + libraryId + '/related');
      },

      ////////////////////////////
      // Org Profile           //
      ////////////////////////////
      getOrgProfile: function (handle) {
        return route('/organization/' + handle + '/profile');
      },
      getOrgLibraries: function (handle, filter, opt_page, opt_size) {
        return route('/organization/' + handle + '/libraries', {
          filter: filter,
          page: _.isUndefined(opt_page) ? [] : opt_page,
          size: _.isUndefined(opt_size) ? [] : opt_size
        });
      },

      ////////////////////////////
      // User Profile           //
      ////////////////////////////
      getUserProfile: function (handle) {
        return route('/user/' + handle + '/profile');
      },
      getUserLibraries: function (handle, filter, opt_page, opt_size) {
        return route('/user/' + handle + '/libraries', {
          filter: filter,
          page: _.isUndefined(opt_page) ? [] : opt_page,
          size: _.isUndefined(opt_size) ? [] : opt_size
        });
      },
      getProfileConnections: function (handle, limit) {
        return route('/users/' + handle + '/connections', {n: limit || []});
      },
      getProfileFollowers: function (handle, limit) {
        return route('/users/' + handle + '/followers', {n: limit || []});
      },
      getProfileUsers: function (ids) {
        return route('/users/' + ids.join('.'));
      },
      getMutualConnections: function (userId) {
        return route('/users/' + userId + '/connections/mutual');
      },


      /////////////////////////////
      // User Personas           //
      /////////////////////////////
      getPersonas: function () {
        return route('/user/personas');
      },
      addPersona: function (personaName) {
        return route('/user/personas/' + personaName);
      },
      removePersona: function (personaName) {
        return route('/user/personas/' + personaName);
      }
    };
  }
]);
