'use strict';

angular.module('kifi.socialService', [])

.factory('socialService', [
  'routeService', '$http', 'util', '$rootScope', 'Clutch', '$window', '$q',
  function (routeService, $http, util, $rootScope, Clutch, $window, $q) {

    var networks = [],
        facebook = {},
        linkedin = {},
        gmail = [],
        addressBooks = [],
        expiredTokens = {};

    var clutchConfig = {
      cacheDuration: 5000
    };

    var networksBackend = new Clutch(function () {
      return $http.get(routeService.networks).then(function (res) {
        util.replaceArrayInPlace(networks, res.data);
        util.replaceObjectInPlace(facebook, _.find(networks, function (n) {
          return n.network === 'facebook';
        }));
        util.replaceObjectInPlace(linkedin, _.find(networks, function (n) {
          return n.network === 'linkedin';
        }));

        return res.data;
      });
    }, clutchConfig);

    var addressBooksBackend = new Clutch(function () {
      return $http.get(routeService.abooksUrl).then(function (res) {
        util.replaceArrayInPlace(addressBooks, res.data);
        util.replaceArrayInPlace(gmail, _.filter(addressBooks, function (elem) {
          return elem.origin === 'gmail';
        }));
        return addressBooks;
      });
    }, clutchConfig);

    var api = {
      networks: networks,
      addressBooks: addressBooks,
      refresh: function () {
        return $q.all([addressBooksBackend.get(), networksBackend.get()]);
      },
      facebook: facebook,
      linkedin: linkedin,
      gmail: gmail,

      connectFacebook: function () {
        $window.location.href = routeService.linkNetwork('facebook');
      },

      connectLinkedIn: function () {
        $window.location.href = routeService.linkNetwork('linkedin');
      },

      importGmail: function () {
        $window.location.href = routeService.importGmail;
      },

      disconnectFacebook: function () {
        return $http.post(routeService.disconnectNetwork('facebook')).then(function (res) {
          util.replaceObjectInPlace(facebook, {});
          return res;
        });
      },

      disconnectLinkedIn: function () {
        return $http.post(routeService.disconnectNetwork('linkedin')).then(function (res) {
          util.replaceObjectInPlace(linkedin, {});
          return res;
        });
      },

      setExpiredTokens: function (networks) {
        var obj = {};
        networks.forEach(function (network) {
          obj[network] = true;
        });
        util.replaceObjectInPlace(expiredTokens, obj);
      },

      expiredTokens: expiredTokens
    };

    return api;
  }
]);
