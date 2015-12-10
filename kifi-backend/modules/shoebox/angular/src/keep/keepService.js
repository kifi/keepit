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
        .addMessageToKeepDiscussion(keepId, { text: text })
        .then(getResponseData);
      },

      getMessagesForKeepDiscussion: function (keepId, limit, fromId) {
        return net
        .getMessagesForKeepDiscussion(keepId, limit, fromId)
        .then(getResponseData);
      },

      markDiscussionAsRead: function (keep) {
        // readList = [ {"keep": <keepId1>,  "lastMessage": <msgId1>}, { "keep": <keepId2>, "lastMessage": <msgId2> } ]
        var comments = keep.discussion && keep.discussion.messages;

        if (comments && comments.length) {
          comments = comments.slice(); // so our sort doesn't mutate the original
          var mostRecentComment = comments.sort(bySentAt).pop();

          return net
          .markDiscussionAsRead([{
            keepId: keep.pubId,
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
