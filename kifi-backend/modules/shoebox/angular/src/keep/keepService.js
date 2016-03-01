'use strict';

angular.module('kifi')

.factory('keepService', [
  '$q', 'profileService', 'net',
  function ($q, profileService, net) {
    function getResponseData(response) {
      return response && response.data;
    }

    function bySentAt(a, b) {
      return a.sentAt - b.sentAt;
    }

    //
    var api = {
      addMessageToKeepDiscussion: function (keepId, text) {
        return net
        .addMessageToKeepDiscussion(keepId, 'Kifi.com', { text: text }) // '/kifi-backend/modules/common/app/com/keepit/discussion/MessageSource'
        .then(getResponseData);
      },

      getMessagesForKeepDiscussion: function (keepId, limit, fromId) {
        return net
        .getMessagesForKeepDiscussion(keepId, limit, fromId)
        .then(getResponseData);
      },

      deleteMessageFromKeepDiscussion: function(keepId, messageId) {
        return net
        .deleteMessageFromKeepDiscussion(keepId, { messageId: messageId })
        .then(getResponseData);
      },

      markDiscussionAsRead: function (keepPubId, comments) {
        // readList = [ {"keep": <keepId1>,  "lastMessage": <msgId1>}, { "keep": <keepId2>, "lastMessage": <msgId2> } ]

        if (comments && comments.length) {
          comments = comments.slice(); // so our sort doesn't mutate the original
          var mostRecentComment = comments.sort(bySentAt).pop();

          return net
          .markDiscussionAsRead([{
            keepId: keepPubId,
            lastMessage: mostRecentComment.id
          }])
          .then(getResponseData);
        } else {
          return $q.reject('Cannot mark empty discussion as read.');
        }
      }
    };

    return api;
  }
])

;
