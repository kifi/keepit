'use strict';

angular.module('kifi.socialService', [
  'angulartics'
])

.factory('socialService', [
  'routeService', '$http', 'util', '$rootScope', 'Clutch', '$window', '$q', '$analytics',
  function (routeService, $http, util, $rootScope, Clutch, $window, $q, $analytics) {

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
        $analytics.eventTrack('user_clicked_page', {
          'action': 'connectFacebook'
        });
        $window.location.href = routeService.linkNetwork('facebook');
      },

      connectLinkedIn: function () {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'connectLinkedIn'
        });
        $window.location.href = routeService.linkNetwork('linkedin');
      },

      importGmail: function () {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'importGmail'
        });
        $window.location.href = routeService.importGmail;
      },

      disconnectFacebook: function () {
        return $http.post(routeService.disconnectNetwork('facebook')).then(function (res) {
          util.replaceObjectInPlace(facebook, {});
          $analytics.eventTrack('user_clicked_page', {
            'action': 'disconnectFacebook'
          });
          return res;
        });
      },

      disconnectLinkedIn: function () {
        return $http.post(routeService.disconnectNetwork('linkedin')).then(function (res) {
          util.replaceObjectInPlace(linkedin, {});
          $analytics.eventTrack('user_clicked_page', {
            'action': 'disconnectLinkedin'
          });
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
