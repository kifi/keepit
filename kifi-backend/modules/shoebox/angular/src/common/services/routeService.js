'use strict';

angular.module('kifi')

.factory('routeService', [
  '$location', 'env', 'util',
  function ($location, env, util) {
    function route(url) {
      return env.xhrBase + url;
    }

    function searchRoute(url) {
      return env.xhrBaseSearch + url;
    }

    function formatPicUrl(userId, pictureName, size) {
      return env.picBase + '/users/' + userId + '/pics/' + (size || 200) + '/' + pictureName;
    }

    var queryStr = util.formatQueryString;

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
      importGmail: function () {
        return env.navBase + '/contacts/import?redirectUrl=' + $location.url(); // wtf, why top level route?
      },
      networks: route('/user/networks'),
      profileUrl: route('/user/me'),
      profileSettings: route('/user/settings'),
      logoutUrl: env.navBase + '/logout',
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
        return route('/collections/search') + queryStr({query: query, limit: limit});
      },
      suggestTags: function (libraryId, keepId, query) {
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
        return route('/user/friends') + '?page=' + page + '&pageSize=' + pageSize;
      },
      friendRequest: function (id) {
        return env.xhrBase + '/user/' + id + '/friend';
      },
      libraryShareSuggest: function (libId, opt_query) {
        return route('/libraries/' + libId + '/members/suggest' + queryStr({n: 30, q: opt_query || []}));
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
        return route('/user/connections/all/search' + queryStr({
          query: name,
          limit: limit || 6,
          pictureUrl: 'true'
        }));
      },
      exportKeeps: route('/keeps/export'),
      postDelightedAnswer: route('/user/delighted/answer'),
      cancelDelightedSurvey: route('/user/delighted/cancel'),
      userCloseAccount: route('/user/close'),
      adHocRecos: function (howMany) {
        return route('/recos/adHoc?n=' + howMany);
      },
      recos: function (opts) {
        return route('/recos/topV2' + queryStr({
          more: opts.more,
          recency: opts.recency,
          uriContext: opts.uriContext || [],
          libContext: opts.libContext || []
        }));
      },
      recosPublic: function () {
        return route('/recos/public');
      },
      recoFeedback: function (urlId) {
        return route('/recos/feedback?id=' + urlId);
      },
      libraryRecos: function () {
        return route('/libraries/recos/top');
      },
      libraryRecoFeedback: function (libraryId) {
        return route('/libraries/recos/feedback?id=' + libraryId);
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
        return route('/users/' + username + '/libraries/' + slug + '?showPublishedLibraries=1' + (authToken ? '&authToken=' + authToken : ''));
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
      uploadLibraryCoverImage: function (libraryId, x, y, idealSize) {
        return route('/libraries/' + libraryId + '/image/upload?x=' + x + '&y=' + y + (idealSize ? '&is=' + idealSize : ''));
      },
      positionLibraryCoverImage: function (libraryId) {
        return route('/libraries/' + libraryId + '/image/position');
      },
      removeLibraryCoverImage: function (libraryId) {
        return route('/libraries/' + libraryId + '/image');
      },
      authIntoLibrary: function (username, slug, authToken) {
        return route('/users/' + username + '/libraries/' + slug + '/auth' + queryStr({authToken: authToken || []}));
      },
      copyKeepsFromTagToLibrary: function(libraryId, tagName) {
        return route('/libraries/' + libraryId + '/importTag' + queryStr({tag: tagName}));
      },
      moveKeepsFromTagToLibrary: function(libraryId, tagName) {
        return route('/libraries/' + libraryId + '/moveTag' + queryStr({tag: tagName}));
      },
      getMoreLibraryMembers: function(libraryId, pageSize, offset) {
        return route('/libraries/' + libraryId + '/members?limit=' + pageSize + '&offset=' + (offset * pageSize));
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
        return route('/user/' + username + '/libraries?filter=' + filter +
          (!_.isUndefined(opt_page) ? '&page=' + opt_page : '') +
          (!_.isUndefined(opt_size) ? '&size=' + opt_size : '')
        );
      }
    };
  }
]);
