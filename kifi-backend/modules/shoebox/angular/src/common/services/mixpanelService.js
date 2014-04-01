/**
* inspired by https://github.com/luisfarzati/angulartics/blob/master/src/angulartics-mixpanel.js
*/
(function (angular) {
  'use strict';
  var $window, profileService;

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
      if (profileService.me && profileService.me.experiments) {
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

    function registerPageTrackForUser(mixpanel, path, origin) {
      if (userId) {
        var oldId = mixpanel.get_distinct_id && mixpanel.get_distinct_id();
        try {
          if (!origin) {
            origin = $window.location.origin;
          }
          mixpanel.identify(userId);
          mixpanel.track('user_viewed_page', {
            type: getLocation(path),
            origin: origin,
            userStatus: getUserStatus()
          });
        } finally {
          if (!oldId) {
            mixpanel.identify(oldId);
          }
        }
      } else {
        identifiedViewEventQueue.push(path);
        if (profileService.me && profileService.me.id) {
          userId = profileService.me.id;
          var toSend = identifiedViewEventQueue.slice();
          identifiedViewEventQueue.length = 0;
          toSend.forEach(function (path) {
            registerPageTrackForUser(mixpanel, path, $window.location.origin);
          });
        }
      }
    }

    function registerPageTrackForVisitor(mixpanel, path) {
      mixpanel.track('visitor_viewed_page', {
        type: getLocation(path),
        origin: $window.location.origin
      });
    }

    angulartics.waitForVendorApi('mixpanel', 5000, function (/*mixpanel*/) {
      $analyticsProvider.registerPageTrack(function (path) {
        if (profileService && $window) {
          var mixpanel = $window.mixpanel;
          var normalizedPath = getLocation(path);
          registerPageTrackForVisitor(mixpanel, normalizedPath, $window);
          registerPageTrackForUser(mixpanel, normalizedPath, $window, profileService);
        }
      });
    });

    angulartics.waitForVendorApi('mixpanel', 5000, function (/*mixpanel*/) {
      $analyticsProvider.registerEventTrack(function (action, properties) {
        if ($window) {
          var mixpanel = $window.mixpanel;
          mixpanel.track(action, properties);
        }
      });
    });
  }])
  .run([
      'profileService', '$window',
      function (p, w) {
        $window = w;
        profileService = p;
      }
    ]);

})(angular);
