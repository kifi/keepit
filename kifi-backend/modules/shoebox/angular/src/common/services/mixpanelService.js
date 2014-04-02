/**
* inspired by https://github.com/luisfarzati/angulartics/blob/master/src/angulartics-mixpanel.js
*/
(function (angular) {
  'use strict';
  var $window, $log, profileService;

  /**
   * @name kifi.mixpanel
   * Enables analytics support for Mixpanel (http://mixpanel.com)
   */
  angular.module('kifi.mixpanel', ['angulartics'])
  .config(['$analyticsProvider',
    function ($analyticsProvider) {

    var identifiedViewEventQueue = [];
    var userId = null;

    var locations = {
      yourKeeps: /^\/$/,
      yourFriends: /^\/friends$/,
      tagResults: /^\/tag/,
      searchResults: /^\/find/,
      addFriends: /^\/friends\/(invite|find)$/,
      requests: /^\/friends\/requests$/
    };

    function getLocation(path) {
      for (var loc in locations) {
        if (locations[loc].test(path)) {
          return loc;
        }
      }
      return path;
    }

    function getUserStatus() {
      var userStatus = 'standard';
      if (profileService && profileService.me && profileService.me.experiments) {
        var experiments = profileService.me.experiments;
        if (experiments.indexOf('fake') > -1) {
          userStatus = 'fake';
        }
        else if (experiments.indexOf('admin') > -1) {
          userStatus = 'admin';
        }
      }
      return userStatus;
    }

    function pageTrackForUser(mixpanel, path, origin) {
      if (userId) {
        var oldId = mixpanel.get_distinct_id && mixpanel.get_distinct_id();
        try {
          if (!origin) {
            origin = $window.location.origin;
          }
          mixpanel.identify(userId);
          $log.log('mixpanelService.pageTrackForUser(' + path + '):' + origin);
          mixpanel.track('user_viewed_page', {
            type: getLocation(path),
            origin: origin,
            siteVersion: 2,
            userStatus: getUserStatus()
          });
        } finally {
          if (!oldId) {
            mixpanel.identify(oldId);
          }
        }
      } else {
        identifiedViewEventQueue.push(path);
        if (profileService && profileService.me && profileService.me.id) {
          userId = profileService.me.id;
          var toSend = identifiedViewEventQueue.slice();
          identifiedViewEventQueue.length = 0;
          toSend.forEach(function (path) {
            pageTrackForUser(mixpanel, path, origin);
          });
        }
      }
    }

    function pageTrackForVisitor(mixpanel, path, origin) {
      $log.log('mixpanelService.pageTrackForVisitor(' + path + '):' + origin);
      mixpanel.track('visitor_viewed_page', {
        type: getLocation(path),
        origin: origin,
        siteVersion: 2
      });
    }

    angulartics.waitForVendorApi('mixpanel', 5000, function (/*mixpanel*/) {
      $analyticsProvider.registerPageTrack(function (path) {
        if (profileService && $window) {
          var mixpanel = $window.mixpanel;
          var normalizedPath = getLocation(path);
          var origin = $window.location.origin;
          pageTrackForVisitor(mixpanel, normalizedPath, origin);
          pageTrackForUser(mixpanel, normalizedPath, origin);
        }
      });
    });

    angulartics.waitForVendorApi('mixpanel', 5000, function (/*mixpanel*/) {
      $analyticsProvider.registerEventTrack(function (action, properties) {
        if ($window) {
          var mixpanel = $window.mixpanel;
          $log.log('mixpanelService.eventTrack(' + action + ')', properties);
          mixpanel.track(action, properties);
        }
      });
    });
  }])
  .run([
      'profileService', '$window', '$log',
      function (p, w, l) {
        $window = w;
        profileService = p;
        $log = l;
      }
    ]);

})(angular);
