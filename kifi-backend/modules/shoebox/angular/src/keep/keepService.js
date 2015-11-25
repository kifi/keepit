'use strict';

angular.module('kifi')

.factory('keepService', [
  'profileService', 'net',
  function (profileService, net) {
    //
    var api = {
      addMessageToKeepDiscussion: function (keepId, message) {
        return net.addMessageToKeepDiscussion(keepId, message).then(function (res) {
          // do something
          return res;
        });
      },

      getMessagesForKeepDiscussion: function (keepId, limit, fromId) {
        return net.getMessagesForKeepDiscussion(keepId, limit, fromId).then(function (res) {
          // do something
          return (res && res.data) || [];
        });
      }
    };

    return api;
  }
])

;
