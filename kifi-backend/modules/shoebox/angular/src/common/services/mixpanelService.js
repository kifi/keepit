// inspired by https://github.com/luisfarzati/angulartics/blob/master/src/angulartics-mixpanel.js
(function () {
  'use strict';

  /**
   * @name kifi.mixpanel
   * Enables analytics support for Mixpanel (http://mixpanel.com)
   */
  angular.module('kifi')
  .config(['$analyticsProvider',
    function ($analyticsProvider) {
      $analyticsProvider.firstPageview(false);
      $analyticsProvider.registerPageTrack(trackPage);
      $analyticsProvider.registerEventTrack(trackEvent);

      // another function MUST be responsible for calling $analytics.pageTrack(url) now
      $analyticsProvider.settings.pageTracking.autoTrackVirtualPages = false;
    }
  ])
  .run(['$window', '$log', 'profileService', 'net', '$q',
    function (_$window_, _$log_, _profileService_, _net_, _$q_) {
      $window = _$window_;
      $log = _$log_;
      profileService = _profileService_;
      net = _net_;
      $q = _$q_;
    }
  ]);

  var $window, $log, profileService, net, $q;  // injected before any code below runs
  var identifiedViewEventQueue = [];
  var userId;

  var locations = {
    homeFeed: /^\/$/,  // TODO: this is now recommendations!
    yourFriends: /^\/connections$/,
    searchResults: /^\/find\b/,
    addFriends: /^\/invite$/
  };

  function isAnalyticsEnabled() {
    return $window.mixpanel;
  }

  function trackEventThroughProxy(event, properties)  {
    if (isAnalyticsEnabled()) {
      return net.event([{
        'event': event,
        'properties': properties
      }]);
    }
  }

  function trackEventDirectly(action, props) {
    if ($window.mixpanel) { $window.mixpanel.track(action, props); }
  }

  function trackPage(path, attributes) {
    //$log.log(path, attributes);
    attributes = attributes || {};
    var origin = attributes.origin || $window.location.origin;

    getUser().then(function() {
      pageTrackForUser(path, origin, attributes);
    }, function() {
      pageTrackForVisitor(path, origin, attributes);
    });
  }

  function trackEvent(action, props) {
    //$log.log(action, props);
    if (isAnalyticsEnabled()) { // TODO: fake implementation for tests
      if ('path' in props && !props.type) {
        props.type = getLocation(props.path);
        delete props.path;
      }
      props = _.extend(getAgentProperties(), props, {
        userStatus: getUserStatus(),
        experiments: getExperiments()
      });
      //$log.log('mixpanelService.eventTrack(' + action + ')', props);
      if (profileService.me && profileService.me.id) {
        trackEventThroughProxy(action, props);
      } else {
        trackEventDirectly(action, props);
      }
    }
  }

  function setUserId(userId) {
    if ($window.mixpanel) {
      $window.mixpanel.identify(userId);
    }
  }

  function getLocation(path) {
    for (var loc in locations) {
      if (locations[loc].test(path)) {
        return loc;
      }
    }
    return path;
  }

  function getExperiments() {
    // 'fake' and 'admin' experiments are tracked separately in "userStatus" field
    return _.without(profileService.me && profileService.me.experiments || [], 'fake', 'admin');
  }

  function getUserStatus() {
    var experiments = profileService.me && profileService.me.experiments || [];
    return experiments.indexOf('fake') >= 0 ? 'fake' :
      (experiments.indexOf('admin') >= 0 ? 'admin' : 'standard');
  }

  function getDistinctId() {
    if ($window.mixpanel && $window.mixpanel.get_distinct_id) {
      var distinct_id = $window.mixpanel.get_distinct_id();
      // backwards compatible where this might be a full user object
      return distinct_id.id || distinct_id;
    }

    return null;
  }

  function getUser() {
    return $q(function(resolve, reject) {
      if (profileService.me && profileService.me.id) {
        resolve(profileService.me);
      } else {
        profileService.getMe().then(function(maybeMe) {
          if (maybeMe) { resolve(maybeMe); }
          else { reject(); }
        }, reject);
      }
    });
  }

  function pageTrackForUser(path, origin, attributes) {
    if (userId) {
      var oldId = getDistinctId();
      try {
        origin = origin || $window.location.origin;
        setUserId(userId);
        //$log.log('mixpanelService.pageTrackForUser(' + path + '):' + origin);

        attributes = _.extend({
          type: attributes.type || getLocation(path),
          origin: origin,
          siteVersion: 2,
          userStatus: getUserStatus(),
          experiments: getExperiments()
        }, attributes);

        trackEventThroughProxy('user_viewed_page', attributes);
      } finally {
        if (oldId) { setUserId(oldId); }
      }
    } else {
      identifiedViewEventQueue.push(path);

      var trackMe = function (me) {
        me = me || profileService.me;
        if (!me || !me.id) {
          // shouldn't happen, just a sanity check
          return;
        }

        userId = me.id;
        var toSend = identifiedViewEventQueue.slice();
        identifiedViewEventQueue.length = 0;
        toSend.forEach(function (path) {
          pageTrackForUser(path, origin, attributes);
        });
      };

      getUser().then(trackMe)['catch'](function(err) {
        // shouldn't happen because pageTrackForUser shouldn't be called if getUser() does not resolve a user
        $log.error('Unexpected rejected getUser()', err);
      });
    }
  }

  function pageTrackForVisitor(path, origin, attributes) {
    //$log.log('mixpanelService.pageTrackForVisitor(' + path + '):' + origin);

    attributes = _.extend({
      type: attributes.type || getLocation(path),
      origin: origin,
      siteVersion: 2
    }, attributes);

    trackEventDirectly('visitor_viewed_page', attributes);
  }

  function getAgentProperties() {
    return {
      os_name: null,
      os_version: null,
      device_model: null
    };
  }

})();
