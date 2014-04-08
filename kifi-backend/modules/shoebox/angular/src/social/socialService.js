'use strict';

angular.module('kifi.socialService', [
  'angulartics'
])

.factory('socialService', [
  'routeService', '$http', 'util', '$rootScope', 'Clutch', '$window', '$q', '$analytics', '$timeout',
  function (routeService, $http, util, $rootScope, Clutch, $window, $q, $analytics, $timeout) {

    var networks = [],
        facebook = {},
        linkedin = {},
        gmail = [],
        addressBooks = [],
        expiredTokens = {},
        isRefreshing = false,
        importStarted = false,
        refreshing = { network: {}, abook: {} };

    var clutchConfig = {
      cacheDuration: 5000
    };

    var updateCheck = function (times) {
      times = times || 10;
      if (times < 0) {
        // We've looped enough times. Clean up, and stop trying.
        isRefreshing = false;
        importStarted = false;
        util.replaceObjectInPlace(refreshing, { network: {}, abook: {} });
        return;
      }

      $http.get(routeService.importStatus).then(function (res) {
        util.replaceObjectInPlace(refreshing, res);

        if (_.size(res.network) > 0 || _.size(res.abook) > 0) {
          // Did we just start an import?
          importStarted = true;
          $timeout(function () {
            updateCheck(--times);
          }, 1000);
        } else {
          // Empty object returned just now.
          if (importStarted) {
            // We previously knew about an import, and since it's empty, we're done.
            isRefreshing = importStarted = false;
            return;
          } else {
            // No import ongoing, and we've never seen evidence of an import. Check again.
            isRefreshing = true;
            $timeout(function () {
              updateCheck(--times);
            }, 3000);
          }
        }

      });
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

      refreshNetworks: function () {
        isRefreshing = true;
        updateCheck();
        // init refreshing polling
        return $http.post(routeService.refreshNetworks);
      },

      isRefreshing: isRefreshing,

      refreshing: refreshing,

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
