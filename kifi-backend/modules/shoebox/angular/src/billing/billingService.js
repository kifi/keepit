'use strict';

angular.module('kifi')

.factory('billingService', [
  'net', 'orgProfileService',
  function (net, orgProfileService) {
    function getResponseData(response) {
      return response.data;
    }

    function invalidateCache() {
      [
        net.getBillingState,
        net.getBillingContacts,
        net.getBillingEvents,
        net.getBillingPlans,
        net.getReferralCode,
        net.getRewards
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
      updateAccountState: function (pubId, newPlanId, newCardId) {
        return net
        .updateAccountState(pubId, newPlanId, newCardId)
        .then(getResponseData);
      },
      getBillingStatePreview: function (pubId, newPlanId, newCardId) {
        return net
        .getBillingStatePreview(pubId, newPlanId, newCardId)
        .then(getResponseData);
      },
      createNewCard: function (pubId, stripeToken) {
        return net
        .createNewCard(pubId, { token: stripeToken })
        .then(getResponseData)
        .then(function (cardData) {
          invalidateCache();
          return cardData.card;
        });
      },
      getDefaultCard: function (pubId) {
        return net
        .getDefaultCard(pubId)
        .then(getResponseData);
      },
      setDefaultCard: function (pubId, cardId) {
        return net
        .setDefaultCard(pubId, { cardId: cardId })
        .then(function (response) {
          invalidateCache();
          orgProfileService.invalidateOrgProfileCache();
          return getResponseData(response);
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
          orgProfileService.invalidateOrgProfileCache();
          return getResponseData(response);
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
      getReferralCode: function (pubId) {
        return net
        .getReferralCode(pubId)
        .then(getResponseData);
      },
      applyReferralCode: function(pubId, code) {
        return net
        .applyReferralCode(pubId, { code: code })
        .then(function (response) {
          invalidateCache();
          return response;
        });
      },
      getRewards: function (pubId) {
        return net
        .getRewards(pubId)
        .then(getResponseData);
      },

      invalidateCache: invalidateCache
    };

    return api;
  }
]);
