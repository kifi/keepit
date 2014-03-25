'use strict';

angular.module('kifi.inviteService', [])

.factory('inviteService', [
  '$http', 'env', '$q',
  function ($http, env, $q, $rootScope) {
    /* Naming convention:
     *  - Kifi Friend is an existing connection on Kifi
     *  - Kifi User is a user of Kifi, may not be a friend.
     */
    var whoToInviteList = [],
        inviteList = [], // used for typeahead dropdown for invite search
        platformFilter;

    var api = {

      invite: function (platform, identifier) {
        return null; // todo!
      },

      getWhoToInvite: function () {
        // use $http if request is needed
        return $q.when(whoToInviteList); // todo!
      },

      find: function (query, platform) {
        if (platform === undefined) {
          // handle no platform, which means search everywhere
        }
        return null; // todo!
      }

    };

    return api;
  }
]);
