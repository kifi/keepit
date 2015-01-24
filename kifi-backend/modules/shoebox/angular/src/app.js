'use strict';

angular.module('kifi', [
  'ngCookies',
  'ngResource',
  'ngSanitize',
  'ngAnimate',
  'ui.router',
  'util',
  'dom',
  'antiscroll',
  'nodraginput',
  'jun.smartScroll',
  'angularMoment',
  'jun.facebook',
  'ui.slider',
  'angulartics',
  'kifi.templates'
])

// fix for when ng-view is inside of ng-include:
// http://stackoverflow.com/questions/16674279/how-to-nest-ng-view-inside-ng-include
// (12-12-2014) Note that we have switched from ng-view to ui-view, but the following
//              is still needed (probably because ui-view is nested inside ng-include).
.run(['$state', angular.noop])

.constant('linkedinConfigSettings', {
  appKey: 'r11loldy9zlg'
})

.constant('env', (function () {
  var location = window.location;
  var host = location.hostname;
  var port = location.port;
  var prod = host === 'www.kifi.com';
  var local = !prod && port === '9000';
  var origin = location.protocol + '//' + host  + (port ? ':' + port : '');
  var navOrigin = local ? origin : 'https://www.kifi.com';

  return {
    local: local,
    dev: !prod,
    production: prod,
    origin: origin,
    navBase: navOrigin,
    xhrBase: navOrigin + '/site',
    xhrBaseEliza: navOrigin.replace('www', 'eliza') + '/eliza/site',
    xhrBaseSearch: navOrigin.replace('www', 'search'),
    picBase: (local ? '//d1scct5mnc9d9m' : '//djty7jcqog9qu') + '.cloudfront.net'
  };
}()))

.config([
  '$FBProvider', 'env',
  function ($FBProvider, env) {
    $FBProvider
      .appId(env.dev ? '530357056981814' : '104629159695560')
      // https://developers.facebook.com/docs/facebook-login/permissions
      .scope('public_profile,user_friends,email')
      .cookie(true)
      .logging(false);
  }
])

.factory('initParams', [
  '$location',
  function ($location) {
    var params = ['m', 'o', 'friend', 'subtype', 'install'];
    var search = $location.search();
    var state = _.pick(search, params);
    if (!_.isEmpty(state)) {
      $location.search(_.omit(search, params)).replace();
    }
    return state;
  }
])

.factory('analyticsState', [
  'initParams',
  function (initParams) {
    // this is a way to add custom event attributes analytics events that may
    // be fired before the full state of the page is realized (like whether or
    // not to load a directive that depends on initParams)
    var attributes = {
      events: {
        user_viewed_page: {}
      }
    };

    if (initParams.friend && /^[a-f0-9-]{36}$/.test(initParams.friend)) {
      // naively assumes that state.friend is a valid externalId and the user
      // will see the contact jointed banner
      attributes.events.user_viewed_page.subtype = initParams.subtype || 'contactJoined';
    }

    return attributes;
  }
])

.controller('AppCtrl', [
  '$scope', 'profileService', '$rootScope', 'friendService', 'libraryService', '$timeout', '$log',
  'platformService', '$rootElement', '$analytics', '$location', 'util',
  function ($scope, profileService, $rootScope, friendService, libraryService, $timeout, $log,
      platformService, $rootElement, $analytics, $location, util) {
    $log.log('\n   █   ● ▟▛ ●        made with ❤\n   █▟▛ █ █■ █    kifi.com/about/team\n   █▜▙ █ █  █         join us!\n');

    function start() {
      if (platformService.isSupportedMobilePlatform()) {
        $rootElement.find('html').addClass('kf-mobile');
      }
      $timeout(function () {
        profileService.fetchMe().then(function () {
          if ($rootScope.userLoggedIn) {
            profileService.fetchPrefs();
            friendService.getRequests();
            libraryService.fetchLibrarySummaries(true);
          }
        });
      });

      // track the current page; this requires angulartics autoTrackVirtualPages
      // setting be false, or we will duplicate 2 page-track events
      if (!$analytics.settings.pageTracking.autoTrackVirtualPages) {
        $rootScope.$on('$stateChangeSuccess', function (event, toState) {
          // We cannot track the library and profile pages yet because we have not loaded
          // information about the library/profile; therefore, libraries and profiles are
          // responsible for calling pageTrack themselves.
          if (!util.startsWith(toState.name, 'library') && !util.startsWith(toState.name, 'userProfile')) {
            var url = $analytics.settings.pageTracking.basePath + $location.url();
            $analytics.pageTrack(url);
          }
        });
      }

      $rootScope.$on('$stateChangeError', function (event, toState, toParams, fromState, fromParams, error) {
        if (error && error.status === 404) {
          event.preventDefault();  // stay in error state
          $scope.errorStatus = 404;
        }
      });
    }

    start();

    $rootScope.$on('appStart', start);
  }
]);
