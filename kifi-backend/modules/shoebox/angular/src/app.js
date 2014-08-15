'use strict';

angular.module('kifi', [
  'ngCookies',
  'ngResource',
  'ngRoute',
  'ngSanitize',
  'ngAnimate',
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
  'kifi.keepView',
  'kifi.helprank',
  'kifi.profile',
  'kifi.friends',
  'kifi.friendService',
  'kifi.friends.friendCard',
  'kifi.friends.friendRequestCard',
  'kifi.friends.rightColFriendsView',
  'kifi.friends.seeMutualFriends',
  'kifi.social',
  'kifi.social.networksNeedAttention',
  'kifi.socialService',
  'kifi.invite',
  'kifi.invite.connectionCard',
  'kifi.invite.wtiService',
  'kifi.savePymkService',
  'kifi.focus',
  'kifi.youtube',
  'kifi.templates',
  'kifi.profileService',
  'kifi.tags',
  'kifi.keeps',
  'kifi.keep',
  'kifi.addKeep',
  'kifi.recos',
  'kifi.recoService',
  'kifi.tagList',
  'kifi.layout.header',
  'kifi.layout.main',
  'kifi.layout.nav',
  'kifi.layout.rightCol',
  'kifi.undo',
  'kifi.installService',
  'kifi.dragService',
  'jun.facebook',
  'ui.slider',
  'angulartics',
  'kifi.mixpanel',
  'kifi.alertBanner',
  'kifi.minVersion',
  'kifi.sticky',
  'kifi.delighted',
  'kifi.userService'
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
      dev = /^dev\.ezkeep\.com|localhost$/.test(host),
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
        $location.search({}).replace();
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

.factory('analyticsState', [
  'injectedState',
  function (injectedState) {
    // this is a way to add custom event attributes analytics events that may
    // be fired before the full state of the page is realized (like whether or
    // not to load a directive that depends on injectedState)
    var attributes = {
      events: {
        user_viewed_page: {}
      }
    };

    var state = injectedState.state || {};
    if (typeof state.friend === 'string' && state.friend.match(/^[a-f0-9-]{36}$/)) {
      // naively assumes that state.friend is a valid externalId and the user
      // will see the contact jointed banner
      attributes.events.user_viewed_page.subtype = 'contactJoined';
    }

    return attributes;
  }
])

.controller('AppCtrl', [
  'profileService', '$window', '$rootScope', 'friendService', '$timeout', '$log',
  function (profileService, $window, $rootScope, friendService, $timeout, $log) {
    $log.log('\n   █   ●  ▟▛ ●        made with ❤\n   █▟▛ █ ■█■ █    kifi.com/about/team\n   █▜▙ █  █  █         join us!\n');
    $timeout(function () {
      profileService.fetchPrefs();
      friendService.getRequests();
      // TODO: add a link for triggering a bookmark import
      // $window.postMessage('get_bookmark_count_if_should_import', '*'); // may get {bookmarkCount: N} reply message
    });
  }
]);
