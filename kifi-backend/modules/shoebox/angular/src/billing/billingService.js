'use strict';

angular.module('kifi')

.factory('billingService', [
  '$analytics', 'net',
  function ($analytics, net) {
    function getResponseData(response) {
      return response.data;
    }

    var api = {
      getBillingState: function (pubId) {
        return net
        .getBillingState(pubId)
        .then(getResponseData);
      },
      getBillingContacts: function (pubId) {
        return net
        .getBillingContacts(pubId)
        .then(getResponseData);
      },
      setBillingContacts: function (pubId, contacts) {
        return net
        .setBillingContacts(pubId, contacts)
        .then(function (response) {
          net.getBillingContacts.clearCache();
          return response;
        });
      }
    };

    return api;
  }
]);
