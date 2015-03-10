'use strict';

angular.module('kifi', [
  'ngCookies',
  'ngResource',
  'ngSanitize',
  'ngAnimate',
  'ui.router',
  'util',
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
    var names = ['m', 'o', 'install', 'intent'];
    var search = $location.search();
    var params = _.pick(search, names);
    if (!_.isEmpty(params)) {
      $location.search(_.omit(search, names)).replace();
    }
    return {
      getAndClear: function (name) {
        var value;
        if (name in params) {
          value = params[name];
          delete params[name];
        }
        return value;
      }
    };
  }
])

.controller('AppCtrl', [
  '$scope', '$rootScope', '$rootElement', '$window', '$timeout', '$log', '$analytics', '$location', '$state',
  'profileService', 'platformService', 'libraryService',
  function ($scope, $rootScope, $rootElement, $window, $timeout, $log, $analytics, $location, $state,
      profileService, platformService, libraryService) {
    $log.log('\n   █   ● ▟▛ ●        made with ❤\n   █▟▛ █ █■ █    kifi.com/about/team\n   █▜▙ █ █  █         join us!\n');

    function start() {
      if (platformService.isSupportedMobilePlatform()) {
        $rootElement.find('html').addClass('kf-mobile');
      }
      $timeout(function () {
        profileService.fetchMe().then(function () {
          if ($rootScope.userLoggedIn) {
            profileService.fetchPrefs();
            libraryService.fetchLibraryInfos(true);
          }
        });
      });

      $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams, fromState, fromParams) {
        $scope.errorStatus = $scope.errorParams = null;

        var toStateParts = toState.name.split('.');
        if (fromState.name && (toStateParts[0] !== fromState.name.split('.')[0] || paramsDiffer(toStateParts[0], fromParams, toParams))) {
          $window.document.body.scrollTop = 0;
        }

        // track the current page; this requires angulartics autoTrackVirtualPages
        // setting be false, or we will duplicate 2 page-track events
        if (!$analytics.settings.pageTracking.autoTrackVirtualPages) {
          // We cannot track the library and profile pages yet because we have not loaded
          // information about the library/profile; therefore, libraries and profiles are
          // responsible for calling pageTrack themselves.
          // TODO: The above statement is now false, so we may want to standardize tracking of those page views.
          if (toStateParts[0] !== 'library' && toStateParts[0] !== 'userProfile') {
            var url = $analytics.settings.pageTracking.basePath + $location.url();
            $analytics.pageTrack(url);
          }
        }
      });

      $rootScope.$on('$stateChangeError', function (event, toState, toParams, fromState, fromParams, error) {
        if (error && _.contains([403, 404], error.status)) {
          event.preventDefault();  // stay in error state
          $scope.errorStatus = error.status;
          $scope.errorParams = toParams;
        }
      });

      $scope.libraryMenu = {
        visible: false
      };
    }

    function paramsDiffer(stateName, p1, p2) {
      var paramNames = $state.get(stateName).url.match(/[:?&]\w+/g).map(function (prop) {
        return prop.slice(1);  // remove leading [:?&]
      });
      return !_.isEqual(_.pick(p1, paramNames), _.pick(p2, paramNames));
    }

    start();

    $rootScope.$on('appStart', start);
  }
]);
