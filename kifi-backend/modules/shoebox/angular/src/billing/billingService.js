'use strict';

angular.module('kifi')

.factory('billingService', [
  '$analytics', 'net', '$timeout',
  function ($analytics, net, $timeout) {
    function getResponseData(response) {
      return response.data;
    }

    function invalidateCache() {
      [
        net.getBillingState,
        net.getBillingContacts,
        net.getBillingEvents,
        net.getBillingEventsBefore,
        net.getBillingPlans,
        net.getReferralCode
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
      getBillingEvents: function (pubId, limit) {
        return net
        .getBillingEvents(pubId, limit)
        .then(getResponseData);
      },
      getBillingEventsBefore: function (pubId, limit, beforeTime, beforeId) {
        return net
        .getBillingEventsBefore(pubId, limit, beforeTime, beforeId)
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
          return response;
        });
      },
      getReferralCode: function (pubId) {
        return pubId;
//        return net
//        .getReferralCode(pubId)
//        .then(function (response) {
//          return response;
//        });
      },
      applyReferralCode: function(pubId, code) {
        return $timeout(function() {
          return { pubId: pubId, code: code, creditAdded: 500 };
        });
//        return net
//        .applyReferralCode(pubId, code)
//        .then(function (response) {
//          invalidateCache();
//          return response;
//        });
      },
      invalidateCache: invalidateCache
    };

    return api;
  }
]);
