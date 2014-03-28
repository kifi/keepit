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
    var api = {
      connectWithKifiUser: function (userId) {
        return userId; // todo!
      },

      getKifiFriends: function () {
        if (!friendsRequested) {
          friendsRequested = true;
          return $http.get(routeService.friends).then(function (res) {
            friends.push.apply(friends, res.data.friends);
            console.log(friends)
            return friends;
          });
        } else {
          return $q.when(friends);
        }
      },

      getRequests: function () {
        return $q.when(requests); // todo!
      },

      friends: friends
    };

    return api;
  }
]);
