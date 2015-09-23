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
  MOVE_ORG_LIBRARIES: 'move_org_libraries',
  VIEW_MEMBERS: 'view_members'
})

.constant('ORG_SETTING_VALUE', {
  ADMIN: 'admin',
  MEMBER: 'member',
  ANYONE: 'anyone',
  DISABLED: 'disabled'
})

.factory('orgProfileService', [
  '$window', '$http', '$rootScope', 'routeService', '$q', '$analytics', 'net', 'ml',
  function ($window, $http, $rootScope, routeService, $q, $analytics, net, ml) {
    function invalidateOrgProfileCache() {
      [
        net.getOrgLibraries,
        net.getOrgMembers,
        net.userOrOrg
      ].forEach(function (endpoint) {
        endpoint.clearCache();
      });
    }

    var api = {
      createOrg: function(name) {
        ml.specs.createOrg = new ml.Spec([
          new ml.Expect('Org was assigned a handle', function(handle) { return (handle !== undefined); }),
          new ml.Assert('Created org was returned in 3 seconds or less', 3000)
        ]);
        return net.createOrg({ name: name }).then(function(response) {
          var handle = response.data.organization.handle;
          ml.specs.createOrg.respond(handle);
          return handle;
        });
      },
      sendOrgMemberInvite: function (orgId, inviteFields) {
        return net.sendOrgMemberInvite(orgId, inviteFields)
          .then(function (response) {
            return response.data;
          });
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
      transferOrgMemberOwnership: function (orgId, newOwner) {
        return net.transferOrgMemberOwnership(orgId, newOwner);
      },
      getOrgSettings: function(orgId) {
        return net.getOrgSettings(orgId).then(function(response) {
          return response.data;
        });
      },
      setOrgSettings: function(orgId, data) {
        return net.setOrgSettings(orgId, data).then(function (response) {
          net.getOrgSettings.clearCache();
          return response.data;
        });
      },
      getOrgLibraries: function (orgId, page, size) {
        return net.getOrgLibraries(orgId, {
          offset: page,
          limit: size
        }).then(function (response) {
          return response.data;
        });
      },
      getOrgMembers: function (orgId, offset, limit) {
        return net.getOrgMembers(orgId, {
          offset: offset,
          limit: limit
        }).then(function (response) {
          return response.data;
        });
      },
      modifyOrgMember: function (orgId, memberFields) {
        return net.modifyOrgMember(orgId, memberFields);
      },
      suggestOrgMember: function (orgId, query, limit) {
        if (typeof limit === 'undefined') {
          limit = 10;
        }

        return net.suggestOrgMember(orgId, query, limit).then(function (response) {
          return response.data.members;
        });
      },
      updateOrgProfile: function (orgId, modifiedFields) {
        return net.updateOrgProfile(orgId, modifiedFields);
      },
      userOrOrg: function (handle, authToken) {
        return net.userOrOrg(handle, authToken).then(function (response) {
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
          orgName: organization.name,
          orgId: organization.id
        };

        return defaultAttributes;
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
