'use strict';

angular.module('kifi.friendService', [])

.factory('friendService', [
  '$http', 'env', '$q', 'routeService',
  function ($http, env, $q, routeService) {
    /* Naming convention:
     *  - Kifi Friend is an existing connection on Kifi
     *  - Kifi User is a user of Kifi, may not be a friend.
     */
    var friends = [];
    var requests = [];
    var friendsRequested = false;
    var friendRequestsRequested = false;
    var api = {
      connectWithKifiUser: function (userId) {
        return userId; // todo!
      },

      getKifiFriends: function () {
        if (!friendsRequested) {
          friendsRequested = true;
          return $http.get(routeService.friends).then(function (res) {
            friends.length = 0;
            friends.push.apply(friends, _.filter(res.data.friends, function (friend) {
              return !friend.unfriended;
            }));
            friendsRequested = false;
            return friends;
          });
        } else {
          return $q.when(friends);
        }
      },

      getRequests: function () {
        if (!friendRequestsRequested) {
          friendRequestsRequested = true;
          return $http.get(routeService.incomingFriendRequests).then(function (res) {
            requests.length = 0;
            requests.push.apply(requests, res.data);
            friendRequestsRequested = false;
            return requests;
          });
        } else {
          return $q.when(requests);
        }
      },

      friends: friends,

      requests: requests,

      unSearchFriend: function (userExtId) {
        return $http.post(env.xhrBase + '/user/' + userExtId + '/exclude', {}).then(function () {
          api.getKifiFriends();
        });
      },

      reSearchFriend: function (userExtId) {
        return $http.post(env.xhrBase + '/user/' + userExtId + '/include', {}).then(function () {
          api.getKifiFriends();
        });
      },

      acceptRequest: function (extId) {
        return $http.post(env.xhrBase + '/user/' + extId + '/friend', {}).then(function () {
          api.getRequests();
          api.getKifiFriends();
        });
      },

      ignoreRequest: function (extId) {
        return $http.post(env.xhrBase + '/user/' + extId + '/ignoreRequest', {}).then(function () {
          api.getRequests();
        });
      },

      unfriend: function (userExtId) {
        return $http.post(env.xhrBase + '/user/' + userExtId + '/unfriend', {}).then(function () {
          api.getKifiFriends();
        });
      }


    };

    return api;
  }
]);
