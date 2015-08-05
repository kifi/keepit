'use strict';

angular.module('kifi')

.factory('orgProfileService', [
  '$http', '$rootScope', 'profileService', 'routeService', '$q', '$analytics', 'net',
  function ($http, $rootScope, profileService, routeService, $q, $analytics, net) {
    function invalidateOrgProfileCache() {
      [
        net.getOrgLibraries,
        net.userOrOrg
      ].forEach(function (endpoint) {
        endpoint.clearCache();
      });
    }

    var api = {
      sendOrgMemberInvite: function (orgId, inviteFields) {
        return net.sendOrgMemberInvite(orgId, inviteFields);
      },
      acceptOrgMemberInvite: function (orgId, authToken) {
        invalidateOrgProfileCache();
        return net.acceptOrgMemberInvite(orgId, authToken);
      },
      cancelOrgMemberInvite: function (orgId, cancelFields) {
        return net.cancelOrgMemberInvite(orgId, cancelFields);
      },
      declineOrgMemberInvite: function (orgId) {
        return net.declineOrgMemberInvite(orgId);
      },
      removeOrgMember: function (orgId, removeFields) {
        return net.removeOrgMember(orgId, removeFields);
      },
      getOrgLibraries: function (orgId, page, size) {
        return net.getOrgLibraries(orgId, {
          offset: page,
          limit: size
        }).then(function (response) {
          return response.data;
        });
      },
      getOrgMembers: function (orgId, page, size) {
        return net.getOrgMembers(orgId, {
          offset: page,
          limit: size
        }).then(function (response) {
          return response.data;
        });
      },
      modifyOrgMember: function (orgId, memberFields) {
        return net.modifyOrgMember(orgId, memberFields);
      },
      updateOrgProfile: function (orgId, modifiedFields) {
        return net.updateOrgProfile(orgId, modifiedFields);
      },
      userOrOrg: function (handle) {
        return net.userOrOrg(handle).then(function (response) {
          return response.data;
        });
      },
      uploadOrgAvatar: function (handle, x, y, sideLength, image) {
        invalidateOrgProfileCache();

        return net.uploadOrgAvatar(handle, x, y, sideLength, image).then(function (response) {
          return response.data;
        });
      },

      getCommonTrackingAttributes: function (organization) {
        var defaultAttributes = {
          type: 'orgMembers',
          orgName: organization.name,
          orgId: organization.id
        };

        return defaultAttributes;
      },
      trackEvent: function (eventName, organization, attributes) {
        var defaultAttributes = api.getCommonTrackingAttributes(organization);
        attributes = _.extend(defaultAttributes, attributes || {});
        $analytics.eventTrack(eventName, attributes);
      }
    };

    return api;
  }
]);
