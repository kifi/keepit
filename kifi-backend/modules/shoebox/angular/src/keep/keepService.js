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
        .addMessageToKeepDiscussion(keepId, { text: text, source: 'Kifi.com' })
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
      },

      getActivityForKeepId: function (keepPubId, beforeTime, maxEvents) {
        return net.getActivityForKeepId(keepPubId, {
          eventsBefore: beforeTime,
          maxEvents: maxEvents || 10
        }).then(function (result) {
          return result.data;
        });
      },


      modifyKeepRecipients: function(keepId, usersToAdd, librariesToAdd, emailsToAdd, usersToRemove, librariesToRemove, emailsToRemove) {
        var diff = {
          users: {
            add: usersToAdd, remove: usersToRemove
          },
          libraries: {
            add: librariesToAdd, remove: librariesToRemove
          },
          emails: {
            add: emailsToAdd, remove: emailsToRemove
          },
          source: 'Kifi.com'
        };

        return net.modifyKeepRecipients(keepId, diff);
      },

      suggestRecipientsForKeep: function (query, limit, offset, requested, keepId) {
        return net.suggestRecipientsForKeep({query: query, limit: limit, offset: offset, requested: requested, keepId: keepId})
          .then(getResponseData);
      },

      //filterRecipient is either a user.id || library.id || email.address. result.data.keeps will not contain any keeps with that recipient
      contextForPage: function (url, uriId, paginationContext,  config, filterRecipient) {
        return net.getPageContext({
            url: url,
            uriId: uriId,
            filterRecipient: filterRecipient,
            paginationContext: paginationContext,
            config: config
          }).then(getResponseData);
      },

      getKeepsAtIntersection: function(url, uriId, intersector, paginationContext, limit) {
        return net.getKeepsAtIntersection({
          url: url,
          uriId: uriId,
          intersector: intersector,
          paginationContext: paginationContext,
          limit: limit
        }).then(getResponseData);
      },

      getPageInfo: function(url, uriId, config) {
        return net.getPageInfo({ url: url, uriId: uriId, config: config }).then(getResponseData);
      }
    };

    return api;
  }
])

;
