'use strict';

angular.module('kifi')


.factory('slackService', [
  'net',
  function (net) {

    function dataLens(resp) {
      return resp && resp.data;
    }

    var api = {
      getKifiOrgsForSlackIntegration: function() {
        return net.getKifiOrgsForSlackIntegration()
          .then(function(response) {
            // maybe transform
            return response.data;
          });
      },
      // Returns a link that we should send the client to if they want to add a new integration to a library
      getAddIntegrationLink: function (libraryId) {
        return net.getAddSlackIntegrationLink(libraryId).then(dataLens);
      },
      // Returns a link that we should send the client to if they want to sync all public channels
      publicSync: function (teamId) {
        return net.publicSyncSlack(teamId).then(dataLens);
      },
      // Returns a link that we should send the client to if they want to connect Slack to a team
      connectTeam: function (teamId) {
        return net.connectSlack(teamId).then(dataLens);
      }
    };
    return api;
  }
]);
