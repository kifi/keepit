'use strict';

angular.module('kifi', [
  'ngCookies',
  'ngResource',
  'ngRoute',
  'ngSanitize',
  'ngAnimate',
  'ui.bootstrap',
  //'ui.router',
  'util',
  'dom',
  'antiscroll',
  'nodraginput',
  'jun.smartScroll',
  'angularMoment',
  'kifi.home',
  'kifi.search',
  'kifi.tagKeeps',
  'kifi.profile',
  'kifi.friends',
  'kifi.friendService',
  'kifi.friends.friendCard',
  'kifi.friends.friendRequestCard',
  'kifi.social',
  'kifi.social.networksNeedAttention',
  'kifi.socialService',
  'kifi.invite',
  'kifi.invite.connectionCard',
  'kifi.invite.wtiService',
  'kifi.focus',
  'kifi.youtube',
  'kifi.templates',
  'kifi.profileCard',
  'kifi.profileService',
  'kifi.detail',
  'kifi.tags',
  'kifi.keeps',
  'kifi.keep',
  'kifi.layout.leftCol',
  'kifi.layout.main',
  'kifi.layout.nav',
  'kifi.layout.rightCol',
  'kifi.undo',
  'kifi.addKeeps',
  'kifi.installService',
  'jun.facebook',
  'ngDragDrop',
  'ui.slider',
  'angulartics',
  'kifi.mixpanel',
  'kifi.alertBanner'
])

// fix for when ng-view is inside of ng-include:
// http://stackoverflow.com/questions/16674279/how-to-nest-ng-view-inside-ng-include
.run(['$route', angular.noop])

.config([
  '$routeProvider', '$locationProvider', '$httpProvider',
  function ($routeProvider, $locationProvider, $httpProvider) {
    $locationProvider
      .html5Mode(true)
      .hashPrefix('!');

    $routeProvider.otherwise({
      redirectTo: '/'
    });

    $httpProvider.defaults.withCredentials = true;
  }
])

.constant('linkedinConfigSettings', {
  appKey: 'r11loldy9zlg'
})

.config([
  '$FBProvider',
  function ($FBProvider) {
    // We cannot inject `env` here since factories are not yet available in config blocks
    // We can make `env` a constant if we want to remove duplicate codes, but
    // then we cannot use $location inside `env` initialization
    /* global window */
    var host = window.location.host || window.location.hostname,
      dev = /^dev\.ezkeep\.com|localhost$/.test(host);
    $FBProvider
      .appId(dev ? '530357056981814' : '104629159695560')
      // https://developers.facebook.com/docs/facebook-login/permissions
      .scope('email')
      .cookie(true)
      .logging(false);
  }
])

.factory('env', [
  '$location',
  function ($location) {
    var host = $location.host(),
      dev = /^.+\.ezkeep\.com|localhost$/.test(host),
      local = $location.port() === 9000,
      origin = local ? $location.protocol() + '://' + host  + ':' + $location.port() : 'https://www.kifi.com';

    return {
      local: local,
      dev: dev,
      production: !dev,
      origin: origin,
      xhrBase: origin + '/site',
      xhrBaseEliza: origin.replace('www', 'eliza') + '/eliza/site',
      xhrBaseSearch: origin.replace('www', 'search'),
      picBase: (local ? '//d1scct5mnc9d9m' : '//djty7jcqog9qu') + '.cloudfront.net'
    };
  }
])

.factory('injectedState', [
  '$location',
  function ($location) {
    var state = {};

    if (_.size($location.search()) > 0) {
      // There may be URL parameters that we're interested in extracting.
      _.forOwn($location.search(), function (value, key) {
        state[key] = value;
      });

      if ($location.path() !== '/find') {
        // For now, remove all URL parameters
        $location.search({});
      }
    }

    function pushState(obj) {
      _.forOwn(obj, function (value, key) {
        state[key] = value;
      });
      return state;
    }

    return {
      state: state,
      pushState: pushState
    };
  }
])

.controller('AppCtrl', [
  'profileService', '$rootScope', '$window', 'friendService', '$timeout', 'env',
  function (profileService, $rootScope, $window, friendService, $timeout, env) {
    $timeout(function () {
      profileService.fetchPrefs().then(function (res) {
        // handle onboarding / imports
        if (env.production) {
          if (!res.onboarding_seen) {
            $rootScope.$emit('showGettingStarted');
          } else {
            $window.postMessage('get_bookmark_count_if_should_import', '*'); // may get {bookmarkCount: N} reply message
          }
        }
        return res;
      });
      friendService.getRequests();
    });
  }
]);
