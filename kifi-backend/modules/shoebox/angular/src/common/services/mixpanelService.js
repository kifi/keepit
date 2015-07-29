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
  .run(['$window', '$log', 'profileService', 'net',
    function (_$window_, _$log_, _profileService_, _net_) {
      $window = _$window_;
      $log = _$log_;
      profileService = _profileService_;
      net = _net_;
    }
  ]);

  var $window, $log, profileService, net;  // injected before any code below runs
  var identifiedViewEventQueue = [];
  var userId;

  // used for sanity check against user id
  var uuidRegex = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/;

  var locations = {
    yourKeeps: /^\/$/,  // TODO: this is now recommendations!
    yourFriends: /^\/connections$/,
    searchResults: /^\/find\b/,
    addFriends: /^\/invite$/
  };

  function isAnalyticsEnabled() {
    return $window.amplitude || $window.mixpanel;
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
    if ($window.amplitude) { $window.amplitude.logEvent(action, props); }
  }

  function trackPage(path, attributes) {
    attributes = attributes || {};
    var origin = attributes.origin || $window.location.origin;

    pageTrackForVisitor(path, origin, attributes);
    pageTrackForUser(path, origin, attributes);
  }

  function trackEvent(action, props) {
    if (isAnalyticsEnabled()) { // TODO: fake implementation for tests
      if ('path' in props && !props.type) {
        props.type = getLocation(props.path);
        delete props.path;
      }
      props = _.extend(getAgentProperties(), props, {
        userStatus: getUserStatus(),
        experiments: getExperiments()
      });
      $log.log('mixpanelService.eventTrack(' + action + ')', props);
      if (profileService.me && profileService.me.id) {
        trackEventThroughProxy(action, props);
      } else {
        trackEventDirectly(action, props);
      }
    }
  }

  function setUser(user) {
    if ($window.mixpanel) {
      $window.mixpanel.identify(user);
    }

    // sanity check shouldn't be necessary, but it's to make sure user.id is
    // valid and doesn't accidentally get set to a value that's not unique
    // across users
    if ($window.amplitude && user && user.id && uuidRegex.test(user.id)) {
      // does not call amplitude.setUserId because this id is internal only and will not be known here
      $window.amplitude.setDeviceId(user.id);
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
      return $window.mixpanel.get_distinct_id();
    }

    return null;
  }

  function pageTrackForUser(path, origin, attributes) {
    if (userId) {

      var oldId = getDistinctId();
      try {
        origin = origin || $window.location.origin;
        setUser(userId);
        $log.log('mixpanelService.pageTrackForUser(' + path + '):' + origin);

        attributes = _.extend({
          type: attributes.type || getLocation(path),
          origin: origin,
          siteVersion: 2,
          userStatus: getUserStatus(),
          experiments: getExperiments()
        }, attributes);

        trackEventThroughProxy('user_viewed_page', attributes);
      } finally {
        if (oldId) { setUser(oldId); }
      }
    } else {
      identifiedViewEventQueue.push(path);

      var trackMe = function (me) {
        me = me || profileService.me;
        if (!me || !me.id) {
          // shouldn't happen, just a sanity check
          return;
        }

        userId = me;
        var toSend = identifiedViewEventQueue.slice();
        identifiedViewEventQueue.length = 0;
        toSend.forEach(function (path) {
          pageTrackForUser(path, origin, attributes);
        });
      };

      if (profileService.me && profileService.me.id) {
        trackMe();
      } else {
        profileService.getMe().then(trackMe);
      }
    }
  }

  function pageTrackForVisitor(path, origin, attributes) {
    $log.log('mixpanelService.pageTrackForVisitor(' + path + '):' + origin);

    attributes = _.extend({
      type: attributes.type || getLocation(path),
      origin: origin,
      siteVersion: 2
    }, attributes);

    trackEventDirectly('visitor_viewed_page', attributes);
  }

  function getAgentProperties() {
    var amplitude = $window.amplitude;
    var ua = amplitude && amplitude._ua;
    return {
      language: amplitude && amplitude.options.language || null,
      platform: amplitude && amplitude.options.platform || null,
      os_name: ua && ua.browser.name || null,
      os_version: ua && ua.browser.major || null,
      device_model: ua && ua.os.name || null
    };
  }

})();
