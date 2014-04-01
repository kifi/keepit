'use strict';

angular.module('kifi.socialService', [])

.factory('socialService', [
  'profileService', 'routeService', '$http', 'util',
  function (profileService, routeService, $http, util) {

    var networks = [], me = {};

    function getNetworks() {
      $http.get(routeService.networks).then(function (res) {
        util.replaceArrayInPlace(networks, res.data);
        me.facebookConnected = !!_.find(networks, function (n) {
          return n.network === 'facebook';
        });
        me.linkedInConnected = !!_.find(networks, function (n) {
          return n.network === 'linkedin';
        });
        me.seqNum++;
        return res.data;
      });
    }



    var api = {

      getNetworks: getNetworks


    };

    return api;
  }
]);
