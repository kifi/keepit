'use strict';

angular.module('kifi')


.factory('slackService', [
  'net',
  function (net) {

    //function getResponseData(response) {
    //  return response.data;
    //}

    var api = {
      getKifiOrgsForSlackIntegration: function() {
          return net.getKifiOrgsForSlackIntegration()
              .then(function(response) {
                  // maybe transform
                  return response.data;
              });
      }

    };

    return api;
  }
]);
