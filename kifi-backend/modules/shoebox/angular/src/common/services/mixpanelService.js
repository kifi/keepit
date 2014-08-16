// inspired by https://github.com/luisfarzati/angulartics/blob/master/src/angulartics-mixpanel.js
(function () {
  'use strict';

  /**
   * @name kifi.mixpanel
   * Enables analytics support for Mixpanel (http://mixpanel.com)
   */
  angular.module('kifi.mixpanel', ['kifi', 'angulartics'])
  .config(['$analyticsProvider',
    function ($analyticsProvider) {
      $analyticsProvider.firstPageview(false);
      $analyticsProvider.registerPageTrack(trackPage);
      $analyticsProvider.registerEventTrack(trackEvent);
    }
  ])
  .run(['$window', '$log', 'profileService', 'analyticsState',
    function (_$window_, _$log_, _profileService_, _analyticsState_) {
      $window = _$window_;
      $log = _$log_;
      profileService = _profileService_;
      analyticsState = _analyticsState_;
    }
  ]);

  var $window, $log, profileService, analyticsState;  // injected before any code below runs
  var identifiedViewEventQueue = [];
  var userId;

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

  function trackPage(path) {
    var mixpanel = $window.mixpanel;
    if (mixpanel) { // TODO: fake implementation for tests
      var origin = $window.location.origin;
      pageTrackForVisitor(mixpanel, path, origin);
      pageTrackForUser(mixpanel, path, origin);
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
      mixpanel.track(action, props);
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

  function pageTrackForUser(mixpanel, path, origin) {
    if (userId) {
      var oldId = mixpanel.get_distinct_id && mixpanel.get_distinct_id();
      try {
        origin = origin || $window.location.origin;
        mixpanel.identify(userId);
        $log.log('mixpanelService.pageTrackForUser(' + path + '):' + origin);

        var customAttributes = analyticsState.events.user_viewed_page;
        var attributes = _.extend(customAttributes, {
          type: getLocation(path),
          origin: origin,
          siteVersion: 2,
          userStatus: getUserStatus(),
          experiments: getExperiments()
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

      if (profileService.me && profileService.me.id) {
        trackMe();
      } else {
        profileService.getMe().then(trackMe);
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

})();
