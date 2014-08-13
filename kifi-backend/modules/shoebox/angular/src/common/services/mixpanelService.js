/**
* inspired by https://github.com/luisfarzati/angulartics/blob/master/src/angulartics-mixpanel.js
*/
(function (angular) {
  'use strict';
  var $window, $log, profileService, analyticsState;

  /**
   * @name kifi.mixpanel
   * Enables analytics support for Mixpanel (http://mixpanel.com)
   */
  angular.module('kifi.mixpanel', ['kifi', 'angulartics'])
  .config(['$analyticsProvider',
    function ($analyticsProvider) {

    var identifiedViewEventQueue = [];
    var userId = null;

    var locations = {
      yourKeeps: /^\/$/,
      yourFriends: /^\/friends$/,
      tagResults: /^\/tag\//,
      searchResults: /^\/find\b/,
      addFriends: /^\/invite$/,
      requests: /^\/friends\/requests$/,
      helpRankClicks: /^\/helprank\/clicks?$/,
      helpRankReKeeps: /^\/helprank\/rekeeps?$/
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

          var customAttributes = analyticsState.events.user_viewed_page;
          var attributes = _.extend(customAttributes, {
            type: getLocation(path),
            origin: origin,
            siteVersion: 2,
            userStatus: getUserStatus()
          });

          mixpanel.track('user_viewed_page', attributes);
        } finally {
          if (!oldId) {
            mixpanel.identify(oldId);
          }
        }
      } else {
        identifiedViewEventQueue.push(path);

        var trackMe = function (me) {
          me = me || profileService.me;
          if (!me) {
            // shouldn't happen, just a sanity check
            return;
          }

          userId = me;
          var toSend = identifiedViewEventQueue.slice();
          identifiedViewEventQueue.length = 0;
          toSend.forEach(function (path) {
            pageTrackForUser(mixpanel, path, origin);
          });
        };

        if (profileService) {
          if (profileService.me && profileService.me.id) {
            trackMe();
          } else {
            profileService.getMe().then(trackMe);
          }
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
          var origin = $window.location.origin;
          pageTrackForVisitor(mixpanel, path, origin);
          pageTrackForUser(mixpanel, path, origin);
        }
      });
    });

    angulartics.waitForVendorApi('mixpanel', 5000, function (/*mixpanel*/) {
      $analyticsProvider.registerEventTrack(function (action, props) {
        if ($window) {
          if ('path' in props && !props.type) {
            props.type = getLocation(props.path);
            delete props.path;
          }
          $log.log('mixpanelService.eventTrack(' + action + ')', props);
          $window.mixpanel.track(action, props);
        }
      });
    });
  }])
  .run([
      'profileService', '$window', '$log', 'analyticsState',
      function (p, w, l, as) {
        $window = w;
        profileService = p;
        $log = l;
        analyticsState = as;
      }
    ]);

})(angular);
