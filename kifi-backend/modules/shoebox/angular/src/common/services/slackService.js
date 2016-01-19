'use strict';

angular.module('kifi')


.factory('slackService', [
  'net',
  function (net) {

    //function getResponseData(response) {
    //  return response.data;
    //}

    var api = {
      createOrganizationForSlackTeam: function(slackTeamId) {
        return net.createOrganizationForSlackTeam(slackTeamId)
            .then(function() {
              return null;
            });
      },
      connectSlackTeamToOrganization: function(orgId, slackTeamId) {
        return net.connectSlackTeamToOrganization(orgId, slackTeamId)
            .then(function() {
              return null;
            });
      }

    };

    return api;
  }
]);
