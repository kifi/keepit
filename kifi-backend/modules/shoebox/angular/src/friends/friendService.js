'use strict';

angular.module('kifi.friendService', [])

.factory('friendService', [
  '$http', 'env', '$q',
  function ($http, env, $q) {
    /* Naming convention:
     *  - Kifi Friend is an existing connection on Kifi
     *  - Kifi User is a user of Kifi, may not be a friend.
     */
    var friends = [],
        requests = [];
    var api = {
      connectWithKifiUser: function (userId) {
        return userId; // todo!
      },

      getKifiFriends: function () {
        return $q.when(friends);
      },

      getRequests: function () {
        return $q.when(requests); // todo!
      }
    };

    return api;
  }
]);
