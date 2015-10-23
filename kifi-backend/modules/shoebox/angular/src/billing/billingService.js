'use strict';

angular.module('kifi')

.factory('billingService', [
  '$analytics', 'net', 'orgProfileService',
  function ($analytics, net, orgProfileService) {
    function getResponseData(response) {
      return response.data;
    }

    function invalidateCache() {
      [
        net.getBillingState,
        net.getBillingContacts,
        net.getBillingEvents,
        net.getBillingPlans
      ].forEach(function (endpoint) {
        endpoint.clearCache();
      });
    }

    var api = {
      getBillingState: function (pubId) {
        return net
        .getBillingState(pubId)
        .then(getResponseData);
      },
      getBillingCCToken: function (pubId) {
        return net
        .getBillingCCToken(pubId)
        .then(getResponseData);
      },
      setBillingCCToken: function (pubId, token) {
        return net
        .setBillingCCToken(pubId, { token: token })
        .then(function (response) {
          invalidateCache();
          return response;
        });
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
          invalidateCache();
          return response;
        });
      },
      getBillingEvents: function (pubId, limit, fromId) {
        return net
        .getBillingEvents(pubId, limit, fromId)
        .then(getResponseData);
      },
      getBillingPlans: function (pubId) {
        return net
        .getBillingPlans(pubId)
        .then(getResponseData);
      },
      setBillingPlan: function (pubId, planId) {
        return net
        .setBillingPlan(pubId, planId)
        .then(function (response) {
          invalidateCache();
          orgProfileService.invalidateOrgProfileCache();
          return response;
        });
      },
      invalidateCache: invalidateCache
    };

    return api;
  }
]);
