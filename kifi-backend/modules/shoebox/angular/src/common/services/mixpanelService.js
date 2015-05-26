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

  var locations = {
    yourKeeps: /^\/$/,  // TODO: this is now recommendations!
    yourFriends: /^\/connections$/,
    searchResults: /^\/find\b/,
    addFriends: /^\/invite$/
  };

  function trackEventThroughProxy(event, properties)  {
    return net.event([{
      'event': event,
      'properties': properties
    }]);
  }

  function trackPage(path, attributes) {
    var mixpanel = $window.mixpanel;
    if (mixpanel) { // TODO: fake implementation for tests
      attributes = attributes || {};
      var origin = attributes.origin || $window.location.origin;

      pageTrackForVisitor(mixpanel, path, origin, attributes);
      pageTrackForUser(mixpanel, path, origin, attributes);
    }
  }

  function trackEvent(action, props) {
    var mixpanel = $window.mixpanel;
    if (mixpanel) { // TODO: fake implementation for tests
      if ('path' in props && !props.type) {
        props.type = getLocation(props.path);
        delete props.path;
      }
      _.extend(props, {
        userStatus: getUserStatus(),
        experiments: getExperiments()
      });
      $log.log('mixpanelService.eventTrack(' + action + ')', props);
      if (profileService.me && profileService.me.id) {
        trackEventThroughProxy(action, props);
      } else {
        mixpanel.track(action, props);
      }
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

  function pageTrackForUser(mixpanel, path, origin, attributes) {
    if (userId) {
      var oldId = mixpanel.get_distinct_id && mixpanel.get_distinct_id();
      try {
        origin = origin || $window.location.origin;
        mixpanel.identify(userId);
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
        if (!oldId) {
          mixpanel.identify(oldId);
        }
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
          pageTrackForUser(mixpanel, path, origin, attributes);
        });
      };

      if (profileService.me && profileService.me.id) {
        trackMe();
      } else {
        profileService.getMe().then(trackMe);
      }
    }
  }

  function pageTrackForVisitor(mixpanel, path, origin, attributes) {
    $log.log('mixpanelService.pageTrackForVisitor(' + path + '):' + origin);

    attributes = _.extend({
      type: attributes.type || getLocation(path),
      origin: origin,
      siteVersion: 2
    }, attributes);

    mixpanel.track('visitor_viewed_page', attributes);
  }

})();
