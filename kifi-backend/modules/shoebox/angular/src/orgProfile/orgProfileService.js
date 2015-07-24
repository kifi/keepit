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
      acceptOrgMemberInvite: function (orgId, authToken) {
        invalidateOrgProfileCache();
        return net.acceptOrgMemberInvite(orgId, authToken);
      }
    };

    return api;
  }
]);
