'use strict';

angular.module('kifi')

.factory('routeService', [
  '$location', 'env',
  function ($location, env) {
    function route(url) {
      return env.xhrBase + url;
    }

    function searchRoute(url) {
      return env.xhrBaseSearch + url;
    }

    function formatPicUrl(userId, pictureName, size) {
      return env.picBase + '/users/' + userId + '/pics/' + (size || 200) + '/' + pictureName;
    }

    return {
      disconnectNetwork: function (network) {
        return env.navBase + '/disconnect/' + network;
      },
      linkNetwork: function (network) {
        return env.navBase + '/link/' + network;
      },
      uploadBookmarkFile: function (makePublic) {
        var path = '/keeps/file-import';
        if (makePublic) {
          path += '?public=1';
        }
        return route(path);
      },
      uploadBookmarkFileToLibrary: function (libraryId) {
        return route('/libraries/' + libraryId + '/import-file');
      },
      refreshNetworks: env.navBase + '/friends/invite/refresh', // would love to be more ajax-y
      importStatus: route('/user/import-status'),
      prefs: route('/user/prefs'),
      importGmail: function () {
        return env.navBase + '/contacts/import?redirectUrl=' + $location.url(); // wtf, why top level route?
      },
      networks: route('/user/networks'),
      profileUrl: route('/user/me'),
      logout: '/logout',
      emailInfoUrl: route('/user/email'),
      abooksUrl: route('/user/abooks'),
      resendVerificationUrl: route('/user/resend-verification'),
      userPasswordUrl: route('/user/password'),
      formatPicUrl: formatPicUrl,
      removeSingleKeep: function (id) {
        return env.xhrBase + '/keeps/' + id + '/delete';
      },
      getKeep: function (keepId) {
        return route('/keeps/' + keepId);
      },
      removeKeeps: route('/keeps/remove'),

      ////////////////////////////
      // Tags                   //
      ////////////////////////////
      tagOrdering: route('/collections/ordering'),
      reorderTag: route('/collections/reorderTag'),
      pageTags: route('/collections/page'),

      searchTags: function (query, limit) {
        return route('/collections/search') + '?query=' + query + '&limit=' + limit;
      },

      suggestTags: function (libraryId, keepId, query) {
        return env.navBase + '/ext/libraries/' + libraryId + '/keeps/' + keepId + '/tags/suggest?q=' + query;
      },
      tagKeep: function (libraryId, keepId, tag) {
        return route('/libraries/' + libraryId + '/keeps/' + keepId + '/tags/' + tag);
      },
      tagKeeps: function (tag) {
        return route('/tags/' + tag);
      },
      untagKeep: function (libraryId, keepId, tag) {
        return route('/libraries/' + libraryId + '/keeps/' + keepId + '/tags/' + tag);
      },

      whoToInvite: route('/user/invite/recommended'),
      blockWtiConnection: route('/user/invite/hide'),
      friends: function (page, pageSize) {
        return route('/user/friends') + '?page=' + page + '&pageSize=' + pageSize;
      },
      friendRequest: function (id) {
        return env.xhrBase + '/user/' + id + '/friend';
      },
      libraryShareSuggest: function (libId, opt_query) {
        return route('/libraries/' + libId + '/members/suggest?n=100' + (opt_query ? '&q=' + opt_query : ''));
      },
      incomingFriendRequests: route('/user/incomingFriendRequests'),
      invite: route('/user/invite'),
      peopleYouMayKnow: function (offset, limit) {
        return route('/user/friends/recommended') + '?offset=' + offset + '&limit=' + limit;
      },
      hideUserRecommendation: function (id) {
        return route('/user/' + id + '/hide');
      },
      search: searchRoute('/site/search2'),
      searchResultClicked: searchRoute('/site/search/events/resultClicked'),
      searchedAnalytics: searchRoute('/site/search/events/searched'),
      searchResultClickedAnalytics: searchRoute('/site/search/events/resultClicked'),
      socialSearch: function (name, limit) {
        limit = limit || 6;
        return route('/user/connections/all/search?query=' + name + '&limit=' + limit + '&pictureUrl=true');
      },
      exportKeeps: route('/keeps/export'),
      postDelightedAnswer: route('/user/delighted/answer'),
      cancelDelightedSurvey: route('/user/delighted/cancel'),
      userCloseAccount: route('/user/close'),
      adHocRecos: function (howMany) {
        return route('/recos/adHoc?n=' + howMany);
      },
      recos: function (opts) {
        return route('/recos/top?more=' + opts.more + '&recency=' + opts.recency);
      },
      recosPublic: function () {
        return route('/recos/public');
      },
      recoFeedback: function (urlId) {
        return route('/recos/feedback?id=' + urlId);
      },
      basicUserInfo: function (id, friendCount) {
        friendCount = friendCount ? 1 : 0;
        return route('/user/' + id + '?friendCount=' + friendCount);
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
      socialSignup: function (provider) {
        return env.navBase + '/auth/token-signup/' + provider;
      },
      socialFinalize: env.navBase + '/auth/token-finalize',
      emailSignup: env.navBase + '/auth/email-signup',

      ////////////////////////////
      // Libraries              //
      ////////////////////////////
      getLibrarySummaries: route('/libraries'),
      getLibraryByUserSlug: function (username, slug, authToken) {
        return route('/users/' + username + '/libraries/' + slug + (authToken ? '?authToken=' + authToken : ''));
      },
      getLibraryById: function (libraryId) {
        return route('/libraries/' + libraryId);
      },
      getLibrarySummaryById: function (libraryId) {
        return route('/libraries/' + libraryId + '/summary');
      },
      getKeepsInLibrary: function (libraryId, count, offset, authToken) {
        return route('/libraries/' + libraryId + '/keeps?count=' + count + '&offset=' + offset + (authToken ? '&authToken=' + authToken : ''));
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
      authIntoLibrary: function (username, slug, authToken) {
        return route('/users/' + username + '/libraries/' + slug + '/auth?authToken=' + authToken || '');
      },
      copyKeepsFromTagToLibrary: function(libraryId, tagName) {
        return route('/libraries/' + libraryId + '/importTag?tag=' + tagName);
      },
      moveKeepsFromTagToLibrary: function(libraryId, tagName) {
        return route('/libraries/' + libraryId + '/moveTag?tag=' + tagName);
      },
      getMoreLibraryMembers: function(libraryId, pageSize, offset) {
        return route('/libraries/' + libraryId + '/members?limit=' + pageSize + '&offset=' + (offset * pageSize));
      }
    };
  }
]);
