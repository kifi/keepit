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
        isRefreshingSocialGraph = false,
        importStarted = false,
        refreshingGraphs = { network: {}, abook: {} },
        updateLock = false;

    var clutchConfig = {
      cacheDuration: 5000
    };

    var checkIfUpdatingGraphs = function (times) {
      if (updateLock) {
        return;
      }

      updateLock = true;
      times = times === undefined ? 10 : times;
      if (times < 0) {
        // We've looped enough times. Clean up, and stop trying.
        importStarted = false;
        util.replaceObjectInPlace(refreshingGraphs, { network: {}, abook: {} });
        updateLock = false;
        return;
      }

      $http.get(routeService.importStatus).then(function (res) {
        util.replaceObjectInPlace(refreshingGraphs, res.data);

        if (_.size(res.data.network) > 0 || _.size(res.data.abook) > 0) {
          isRefreshingSocialGraph = true;
          if (!importStarted) {
            // Did we just start an import?
            importStarted = true;
            times = 20; // reset times, so we'll check more.
          }
          $timeout(function () {
            updateLock = false;
            checkIfUpdatingGraphs(--times);
          }, 2000);
          return;
        } else {
          // Empty object returned just now.
          if (importStarted) {
            // We previously knew about an import, and since it's empty, we're done.
            isRefreshingSocialGraph = importStarted = false;
            updateLock = false;
            $rootScope.$emit('social.updated');
            return;
          } else {
            // No import ongoing, and we've never seen evidence of an import. Check again.
            $timeout(function () {
              updateLock = false;
              checkIfUpdatingGraphs(--times);
            }, 3000);
          }
        }

      });
    };

    var networksBackend = new Clutch(function () {
      return $http.get(routeService.networks).then(function (res) {
        _.remove(res.data, function (value) {
          return value.network === 'fortytwo';
        });
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
        return res.data;
      });
    }, clutchConfig);

    var api = {
      networks: networks,
      addressBooks: addressBooks,
      refresh: function () {
        checkIfUpdatingGraphs(1);
        return $q.all([addressBooksBackend.get(), networksBackend.get()]);
      },
      facebook: facebook,
      linkedin: linkedin,
      gmail: gmail,

      refreshSocialGraph: function () {
        isRefreshingSocialGraph = true;
        checkIfUpdatingGraphs(); // init refreshing polling
        return $http.post(routeService.refreshNetworks);
      },

      checkIfUpdatingGraphs: checkIfUpdatingGraphs,

      checkIfRefreshingSocialGraph: function () {
        return isRefreshingSocialGraph;
      },

      refreshingGraphs: refreshingGraphs,

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
        $window.location.href = routeService.importGmail();
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
