'use strict';

angular.module('kifi.routeService', [])

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
        return env.origin + '/disconnect/' + network;
      },
      linkNetwork: function (network) {
        return env.origin + '/link/' + network;
      },
      uploadBookmarkFile: function(makePublic) {
        var path = '/keeps/file-import';
        if (makePublic) {
          path += '?public=1';
        }
        return route(path);
      },
      refreshNetworks: env.origin + '/friends/invite/refresh', // would love to be more ajax-y
      importStatus: route('/user/import-status'),
      prefs: route('/user/prefs'),
      importGmail: function () {
        return env.origin + '/contacts/import?redirectUrl=' + $location.url(); // wtf, why top level route?
      },
      networks: route('/user/networks'),
      profileUrl: route('/user/me'),
      logout: 'https://www.kifi.com/logout',
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
      tagOrdering: route('/collections/ordering'),
      reorderTag: route('/collections/reorderTag'),
      whoToInvite: route('/friends/wti'),
      blockWtiConnection: route('/friends/wti/block'),
      friends: function (page, pageSize) {
        return route('/user/friends') + '?page=' + page + '&pageSize=' + pageSize;
      },
      friendRequest: function (id) {
        return env.xhrBase + '/user/' + id + '/friend';
      },
      incomingFriendRequests: route('/user/incomingFriendRequests'),
      invite: route('/user/invite'),
      search: searchRoute('/site/search'),
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
      basicUserInfo: function (id, friendCount) {
        friendCount = friendCount ? 1 : 0;
        return route('/user/' + id + '?friendCount=' + friendCount);
      }
    };
  }
]);
