'use strict';

angular.module('kifi')

.factory('routeService', [
  'env', 'util',
  function (env, util) {
    var cdnImageBase = 'https://djty7jcqog9qu.cloudfront.net/'; // for dynamic images (keeps, library images, user profile pics, etc.)
    var cdnAssetBase = 'https://d1dwdv9wd966qu.cloudfront.net/'; // for static assets (icons)

    function route(path, params) {
      return env.xhrBase + path + (params ? queryStr(params) : '');
    }

    function searchRoute(path) {
      return env.xhrBaseSearch + path;
    }

    function navRoute(path, params) {
      return env.navBase + path + (params ? queryStr(params) : '');
    }

    function formatPicUrl(userId, pictureName, size) {
      return env.picBase + '/users/' + userId + '/pics/' + (size || 200) + '/' + pictureName;
    }

    var queryStr = util.formatQueryString;

    return {
      cdnImageBase : cdnImageBase,
      cdnAssetBase : cdnAssetBase,
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
      logoutUrl: '/logout',
      emailInfoUrl: route('/user/email'),
      abooksUrl: route('/user/abooks'),
      resendVerificationUrl: route('/user/resend-verification'),
      userPasswordUrl: route('/user/password'),
      formatPicUrl: formatPicUrl,
      libraryImageUrl: function (path) {
        return env.picBase + '/' + path;
      },
      getKeep: function (keepId) {
        return route('/keeps/' + keepId);
      },

      ////////////////////////////
      // Tags                   //
      ////////////////////////////
      tagOrdering: route('/collections/ordering'),
      reorderTag: route('/collections/reorderTag'),
      pageTags: route('/collections/page'),

      searchTags: function (query, limit) {
        return route('/collections/search', {query: query, limit: limit});
      },
      suggestTags: function (libraryId, keepId, query) {
        // TODO: stop using extension endpoint
        return env.navBase + '/ext/libraries/' + libraryId + '/keeps/' + keepId + '/tags/suggest' + queryStr({q: query});
      },
      tagKeep: function (libraryId, keepId, tag) {
        return route('/libraries/' + libraryId + '/keeps/' + keepId + '/tags/' + encodeURIComponent(tag));
      },
      tagKeeps: function (tag) {
        return route('/tags/' + encodeURIComponent(tag));
      },
      untagKeep: function (libraryId, keepId, tag) {
        return route('/libraries/' + libraryId + '/keeps/' + keepId + '/tags/' + encodeURIComponent(tag));
      },

      whoToInvite: route('/user/invite/recommended'),
      blockWtiConnection: route('/user/invite/hide'),
      friends: function (page, pageSize) {
        return route('/user/friends', {page: page, pageSize: pageSize});
      },
      friendRequest: function (id) {
        return env.xhrBase + '/user/' + id + '/friend';
      },
      libraryShareSuggest: function (libId, opt_query) {
        return route('/libraries/' + libId + '/members/suggest', {n: 30, q: opt_query || []});
      },
      incomingFriendRequests: route('/user/incomingFriendRequests'),
      invite: route('/user/invite'),
      peopleYouMayKnow: function (offset, limit) {
        return route('/user/friends/recommended', {offset: offset, limit: limit});
      },
      hideUserRecommendation: function (id) {
        return route('/user/' + id + '/hide');
      },
      search: searchRoute('/site/search2'),
      searchResultClicked: searchRoute('/site/search/events/resultClicked'),
      searchedAnalytics: searchRoute('/site/search/events/searched'),
      searchResultClickedAnalytics: searchRoute('/site/search/events/resultClicked'),
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
          more: opts.more ? 1 : 0,
          recency: opts.recency,
          uriContext: opts.uriContext || [],
          libContext: opts.libContext || []
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
      addKeepsToLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/keeps');
      },
      copyKeepsToLibrary: function () {
        return route('/libraries/copy');
      },
      moveKeepsToLibrary: function () {
        return route('/libraries/move');
      },
      removeKeepFromLibrary: function (libraryId, keepId) {
        return route('/libraries/' + libraryId + '/keeps/' + keepId);
      },
      removeManyKeepsFromLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/keeps/delete');
      },

      ////////////////////////////
      // User registration      //
      ////////////////////////////
      socialSignup: function(provider) {
        return env.navBase + '/signup/' + provider;
      },
      tokenSocialSignup: function (provider) {
        return env.navBase + '/auth/token-signup/' + provider;
      },
      socialSignupWithRedirect: function (provider, redirectPath, intent) {
        return '/signup/' + provider + queryStr({
          redirect: redirectPath,
          intent: intent || []
        });
      },
      socialFinalize: env.navBase + '/auth/token-finalize',
      emailSignup: env.navBase + '/auth/email-signup',

      ////////////////////////////
      // Libraries              //
      ////////////////////////////
      getLibrarySummaries: route('/libraries'),
      getLibraryByUserSlug: function (username, slug, authToken) {
        return route('/users/' + username + '/libraries/' + slug, {showPublishedLibraries: 1, authToken: authToken || []});
      },
      getLibraryById: function (libraryId) {
        return route('/libraries/' + libraryId);
      },
      getLibrarySummaryById: function (libraryId) {
        return route('/libraries/' + libraryId + '/summary');
      },
      getKeepsInLibrary: function (libraryId, count, offset, authToken) {
        return route('/libraries/' + libraryId + '/keeps', {count: count, offset: offset, authToken: authToken || []});
      },
      createLibrary: route('/libraries/add'),
      modifyLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/modify');
      },
      shareLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/invite');
      },
      joinLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/join');
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
      uploadLibraryCoverImage: function (libraryId, x, y, idealSize) {
        return route('/libraries/' + libraryId + '/image/upload', {x: x, y: y, is: idealSize || []});
      },
      positionLibraryCoverImage: function (libraryId) {
        return route('/libraries/' + libraryId + '/image/position');
      },
      removeLibraryCoverImage: function (libraryId) {
        return route('/libraries/' + libraryId + '/image');
      },
      authIntoLibrary: function (username, slug, authToken) {
        return route('/users/' + username + '/libraries/' + slug + '/auth', {authToken: authToken || []});
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
      // User Profile           //
      ////////////////////////////
      getProfileUrl: function (username) {
        return username ? env.origin + '/' + username : null;
      },
      getUserProfile: function (username) {
        return route('/user/' + username + '/profile');
      },
      getUserLibraries: function (username, filter, opt_page, opt_size) {
        return route('/user/' + username + '/libraries', {
          filter: filter,
          page: _.isUndefined(opt_page) ? [] : opt_page,
          size: _.isUndefined(opt_size) ? [] : opt_size
        });
      },

      /////////////////////////////
      // User Personas           //
      /////////////////////////////
      getAllPersonas: function() {
        return route('/user/personas');
      },
      addPersona: function(personaName) {
        return route('/user/personas/' + personaName);
      },
      removePersona: function(personaName) {
        return route('/user/personas/' + personaName);
      }
    };
  }
]);
