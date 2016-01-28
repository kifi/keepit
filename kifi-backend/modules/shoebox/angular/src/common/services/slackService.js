'use strict';

angular.module('kifi')


.factory('slackService', [
  'net',
  function (net) {
    var api = {
      getKifiOrgsForSlackIntegration: function() {
        return net.getKifiOrgsForSlackIntegration()
          .then(function(response) {
            // maybe transform
            return response.data;
          });
      },
      getAddIntegrationLink: function (libraryId) {
        return net.getAddIntegrationLink(libraryId).then(function (resp) {
          return resp && resp.data && resp.data.redirect;
        });
      }
    };
    return api;
  }
]);
