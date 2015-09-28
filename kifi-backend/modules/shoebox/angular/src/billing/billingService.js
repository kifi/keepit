'use strict';

angular.module('kifi')

.factory('billingService', [
  '$analytics', 'net',
  function ($analytics, net) {
    var api = {
      getBillingState: function (pubId) {
        return net
        .getBillingState(pubId)
        .then(function (response) {
          return response.data;
        });
      }
    };

    return api;
  }
]);
