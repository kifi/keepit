'use strict';

angular.module('kifi.routeService', [])

.factory('routeService', [
  'env',
  function (env) {
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
      importGmail: env.origin + '/importContacts', // wtf, why top level route?
      networks: route('/user/networks'),
      profileUrl: route('/user/me'),
      emailInfoUrl: route('/user/email'),
      abooksUrl: route('/user/abooks'),
      resendVerificationUrl: route('/user/resend-verification'),
      userPasswordUrl: route('/user/password'),
      formatPicUrl: formatPicUrl,
      tagOrdering: route('/collections/ordering'),
      whoToInvite: route('/friends/wti'),
      blockWtiConnection: route('/friends/wti/block'),
      friends: route('/user/friends'),
      incomingFriendRequests: route('/user/incomingFriendRequests'),
      invite: route('/user/invite'),
      search: searchRoute('/site/search'),
      searchAnalytics: searchRoute('/site/...'),
      socialSearch: function (name, limit) {
        limit = limit || 6;
        return route('/user/connections/all/search?query=' + name + '&limit=' + limit + '&pictureUrl=true');
      }
    };
  }
]);
