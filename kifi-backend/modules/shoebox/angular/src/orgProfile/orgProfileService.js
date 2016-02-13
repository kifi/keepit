'use strict';

angular.module('kifi')

.constant('ORG_PERMISSION', {
  MANAGE_PLAN: 'manage_plan',
  FORCE_EDIT_LIBRARIES: 'force_edit_libraries',
  INVITE_MEMBERS: 'invite_members',
  GROUP_MESSAGING: 'group_messaging',
  EDIT_ORGANIZATION: 'edit_organization',
  VIEW_ORGANIZATION: 'view_organization',
  REMOVE_LIBRARIES: 'remove_libraries',
  CREATE_SLACK_INTEGRATION: 'create_slack_integration',
  MODIFY_MEMBERS: 'modify_members',
  PUBLISH_LIBRARIES: 'publish_libraries',
  REMOVE_MEMBERS: 'remove_members',
  ADD_LIBRARIES: 'add_libraries',
  VIEW_MEMBERS: 'view_members',
  EXPORT_KEEPS: 'export_keeps',
  VIEW_SETTINGS: 'view_settings',
  REDEEM_CREDIT_CODE: 'redeem_credit_code'
})

.constant('ORG_SETTING_VALUE', {
  DISABLED: 'disabled',
  ADMIN: 'admins',
  MEMBER: 'members',
  NONMEMBERS: 'nonmembers',
  ANYONE: 'anyone'
})

.factory('orgProfileService', [
  '$analytics', 'net', 'profileService',
  function ($analytics, net, profileService) {
    function invalidateOrgProfileCache() {
      [
        net.getOrgLibraries,
        net.getOrgMembers,
        net.userOrOrg,
        net.getOrgSettings,
        net.getOrgDomains
      ].forEach(function (endpoint) {
        endpoint.clearCache();
      });
    }

    function getResponseData(response) {
      return response.data;
    }

    var api = {
      createOrg: function(name) {
        return net
        .createOrg({ name: name })
        .then(function(response) {
          return response.data.organization;
        });
      },
      sendOrgMemberInvite: function (orgId, inviteFields) {
        return net
        .sendOrgMemberInvite(orgId, inviteFields)
        .then(getResponseData);
      },
      acceptOrgMemberInvite: function (orgId, authToken) {
        return net
        .acceptOrgMemberInvite(orgId, authToken)
        .then(invalidateOrgProfileCache)
        .then(profileService.fetchMe);
      },
      cancelOrgMemberInvite: function (orgId, cancelFields) {
        return net.cancelOrgMemberInvite(orgId, cancelFields);
      },
      declineOrgMemberInvite: function (orgId) {
        return net
        .declineOrgMemberInvite(orgId)
        .then(profileService.fetchMe);
      },
      removeOrgMember: function (orgId, removeFields) {
        return net
        .removeOrgMember(orgId, removeFields)
        .then(function () {
          invalidateOrgProfileCache();
        })
        .then(profileService.fetchMe);
      },
      transferOrgMemberOwnership: function (orgId, newOwner) {
        return net.transferOrgMemberOwnership(orgId, newOwner);
      },
      getOrgSettings: function(orgId) {
        return net
        .getOrgSettings(orgId)
        .then(getResponseData);
      },
      setOrgSettings: function(orgId, data) {
        return net
        .setOrgSettings(orgId, data)
        .then(function (response) {
          net.getOrgSettings.clearCache();
          invalidateOrgProfileCache();

          return response.data;
        });
      },
      getOrgDomains: function (pubId) {
        return net
        .getOrgDomains(pubId)
        .then(getResponseData);
      },
      addOrgDomain: function (pubId, domain) {
        return net
        .addOrgDomain(pubId, { domain: domain })
        .then(function (response) {
          net.getOrgDomains.clearCache();
          return getResponseData(response);
        });
      },
      addDomainAfterVerification: function (pubId, email) {
        return net
        .addDomainAfterVerification(pubId, { email: email })
        .then(function (response) {
          net.getOrgDomains.clearCache();
          return getResponseData(response);
        });
      },
      removeOrgDomain: function (pubId, domain) {
        return net
        .removeOrgDomain(pubId, { domain: domain })
        .then(function (response) {
          net.getOrgDomains.clearCache();
          return getResponseData(response);
        });
      },
      getOrgLibraries: function (orgId, offset, limit) {
        return net.getOrgLibraries(orgId, {
          offset: offset,
          limit: limit
        })
        .then(getResponseData);
      },
      // optArgs {
      //  ordering: "alphabetical" | "most_recent_keeps_by_user"
      //  direction: "asc" | "desc"
      //  windowSize: #days (used for most_recent_keeps_by_user)
      // }
      getOrgBasicLibraries: function (orgId, offset, limit, optArgs) {
          return net.getOrgBasicLibraries(orgId, offset, limit, optArgs).then(getResponseData);
      },
      getOrgMembers: function (orgId, offset, limit) {
        return net.getOrgMembers(orgId, {
          offset: offset,
          limit: limit
        })
        .then(getResponseData);
      },
      modifyOrgMember: function (orgId, memberFields) {
        return net.modifyOrgMember(orgId, memberFields);
      },
      suggestOrgMember: function (orgId, query, limit) {
        if (typeof limit === 'undefined') {
          limit = 10;
        }

        return net
        .suggestOrgMember(orgId, query, limit)
        .then(function (response) {
          return response.data.members;
        });
      },
      updateOrgProfile: function (orgId, modifiedFields) {
        return net.updateOrgProfile(orgId, modifiedFields);
      },
      userOrOrg: function (handle, authToken) {
        return net
        .userOrOrg(handle, authToken)
        .then(getResponseData);
      },
      uploadOrgAvatar: function (handle, x, y, sideLength, image) {
        return net
        .uploadOrgAvatar(handle, x, y, sideLength, image)
        .then(invalidateOrgProfileCache);
      },
      exportOrgKeeps: function (format, orgIds) {
        return net
        .exportOrgKeeps({
          format: format,
          orgIds: orgIds
        })
        .then(getResponseData);
      },

      getCommonTrackingAttributes: function (organization) {
        var defaultAttributes = {
          orgName: organization.name,
          orgId: organization.id
        };

        return defaultAttributes;
      },
      getSlackIntegrationsForOrg: function (org) {
        return net.getSlackIntegrationsForOrg(org.id).then(getResponseData);
      },
      trackEvent: function (eventName, organization, attributes) {
        var defaultAttributes = api.getCommonTrackingAttributes(organization);
        attributes = _.extend(defaultAttributes, attributes || {});
        $analytics.eventTrack(eventName, attributes);
      },
      invalidateOrgProfileCache: invalidateOrgProfileCache
    };

    return api;
  }
]);
