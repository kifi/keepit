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
  'angular-clipboard',
  'jun.smartScroll',
  'angularMoment',
  'jun.facebook',
  'stripe.checkout',
  'ui.slider',
  'angulartics',
  'kifi.templates',
  'sun.scrollable'
])

// fix for when ng-view is inside of ng-include:
// http://stackoverflow.com/questions/16674279/how-to-nest-ng-view-inside-ng-include
// (12-12-2014) Note that we have switched from ng-view to ui-view, but the following
//              is still needed (probably because ui-view is nested inside ng-include).
.run(['$state', angular.noop])

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
    xhrBaseApi: navOrigin + '/api',
    xhrBaseSearch: navOrigin.replace('www', 'search') + '/site',
    xhrBaseEliza: navOrigin.replace('www', 'eliza') + '/eliza/site',
    picBase: (local ? '//d1scct5mnc9d9m' : '//djty7jcqog9qu') + '.cloudfront.net'
  };
}()))

.config([
  '$compileProvider', '$FBProvider', 'StripeCheckoutProvider', 'env',
  function ($compileProvider, $FBProvider, StripeCheckoutProvider, env) {
    // ng-perf.com/2014/10/24/simple-trick-to-speed-up-your-angularjs-app-load-time/
    $compileProvider.debugInfoEnabled(env.dev);

    $FBProvider
      .appId(env.dev ? '530357056981814' : '104629159695560')
      // https://developers.facebook.com/docs/facebook-login/permissions
      .scope('public_profile,user_friends,email')
      .cookie(true)
      .logging(false);

    StripeCheckoutProvider.defaults({
      key: 'pk_live_xMqAibAbZzNiiZvzPJ2s21e4'
    });
  }
])

// Don't use parentheses to signify negative numbers in the currency filter
.config([
  '$provide',
  function ($provide) {
    $provide.decorator('$locale', [
      '$delegate',
      function($delegate) {
        $delegate.NUMBER_FORMATS.PATTERNS[1].negPre = '-\u00A4';
        $delegate.NUMBER_FORMATS.PATTERNS[1].negSuf = '';
        return $delegate;
      }
    ]);
  }
])

.factory('initParams', [
  '$location',
  function ($location) {
    var names = ['m', 'o', 'install', 'intent', 'showSlackDialog', 'slack', 'forceSlackDialog'];
    var scrub = ['kcid', 'utm_campaign', 'utm_content', 'utm_medium', 'utm_source', 'utm_term', 'dat', 'kma'];
    var search = $location.search();
    var params = _.pick(search, names);
    if (!_.isEmpty(params) || !_.isEmpty(_.pick(search, scrub))) {
      $location.search(_.omit(search, names, scrub)).replace();
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

.factory('$exceptionHandler', [
  '$injector', '$window', '$log',
  function ($injector, $window, $log) {
    function log(exception, cause) {
      if ($window.Airbrake) {
        $window.Airbrake.push({
          error: {
            message: exception.toString(),
            stack: exception.stack
          },
          params: {
            cause: cause
          }
        });
      } else {
        $log.error(exception);
      }
    }

    return _.debounce(log, 5000, true);
  }
])

.factory('errorResponseReporter', [
  '$q', '$exceptionHandler',
  function($q, $exceptionHandler) {
    var ignoredStatuses = [ -1, 0, 403 ];

    var errorResponseReporter = {
      responseError: function (response) {
        // This SHOULD only be called for all HTTP 400-599 status codes.
        response = response || {}; // make sure response is defined

        if (!_.contains(ignoredStatuses, response.status)) {
          $exceptionHandler(new Error('Client received HTTP status ' + response.status), response);
        }

        // Continue treating the response as an error.
        return $q.reject(response);
      }
    };
    return errorResponseReporter;
  }
])

.config([
  '$httpProvider',
  function ($httpProvider) {
    $httpProvider.interceptors.push('errorResponseReporter');
  }
])

.config([
  '$provide',
  function ($provide) {
    $provide.decorator('$http', [
      '$delegate', '$q', '$window',
      function ($delegate, $q, $window) {
        var parser = $window.document.createElement('a');
        function getPathname(url) {
          parser.href = url;
          return parser.pathname + parser.search;
        }

        if (!($window.preload && $window.preload.data)) {
          // Preload hasn't run yet, so we can mock it
          $window.preload = function (path, data) {
            $window.preload.data = $window.preload.data || {};

            var alreadyRequestedDeferred = $window.preload.data[path];
            if (alreadyRequestedDeferred) {
              alreadyRequestedDeferred.resolve({ data : data });
            } else {
              $window.preload.data[path] = data;
            }
          };
        }

        $window.preload.get = function get(path) {
          $window.preload.data = $window.preload.data || {};

          var alreadyLoadedData = $window.preload.data[path];
          var deferred = $q.defer();
          if (alreadyLoadedData) {
            return $q.when({ data: alreadyLoadedData }); // (it expects a response object)
          } else {
            $window.preload.data[path] = deferred;
            return deferred.promise;
          }
        };

        function firstPromiseToFinish(promises) {
          promises = (arguments.length === 1 ? promises : Array.prototype.slice.call(arguments));
          var deferred = $q.defer();

          promises.forEach(function (p) {
            p
            .then(deferred.resolve)
            ['catch'](deferred.reject);
          });

          return deferred.promise;
        }

        var $http = $delegate;

        var wrapper = function (options) {
          var url = options.url;
          var pathname = getPathname(url);

          return firstPromiseToFinish([
            $window.preload.get(pathname),
            $http.apply($http, arguments)
          ]);
        };

        // $http has convenience methods such as $http.get() that we have
        // to pass through as well.
        Object.keys($http).filter(function (key) {
          return (typeof $http[key] === 'function');
        }).forEach(function (key) {
          wrapper[key] = function () {
            var url = arguments[0];
            var pathname = getPathname(url);

            return firstPromiseToFinish([
              $window.preload.get(pathname),
              $http[key].apply($http, arguments)
            ]);
          };
        });

        return wrapper;
      }
    ]);
  }
])

.controller('AppCtrl', [
  '$scope', '$rootScope', '$rootElement', '$window', '$timeout', '$log', '$analytics', '$location', '$state',
  'profileService', 'platformService', 'libraryService',
  function ($scope, $rootScope, $rootElement, $window, $timeout, $log, $analytics, $location, $state,
      profileService, platformService, libraryService) {
    $log.log('\n   █   ● ▟▛ ●        made with ❤\n   █▟▛ █ █■ █    kifi.com/about/join_us\n   █▜▙ █ █  █         join us!\n');

    function start() {
      if ($rootElement.find('#kf-authenticated').removeAttr('id').length) {
        $timeout(function () {
          profileService.fetchMe().then(function () {
            if ($rootScope.userLoggedIn) {
              $rootScope.navBarEnabled = profileService.hasExperiment('new_sidebar');
              profileService.fetchPrefs(true);
              libraryService.fetchLibraryInfos(true);
            }
          });
        });
      } else {
        profileService.initLoggedOut();
      }

      $rootScope.$on('$stateChangeStart', function(evt, to, params) {
        /*
        this allows a way to redirect via the state provider to other states
        example would be the backend sending to a particular 'link' but the frontend
        may choose to change how it is routed, this way the backend doesn't have to
        change where the frontend is routed to, provides a sort of indirection-layer
        for deep linking
        redirectTo object is currently specified as:
        {
          state: 'home.feed', -> the state to go to
          params: {} -> params that would like to be tagged on as well
        }
        params that are on the defined state route (/a/b/:c/?:d) will already
        show up in the params handed in, so we merge the params handed in with
        the redirect params to create a final params object
        */
        if (to.redirectTo) {
          evt.preventDefault();
          var newParams = angular.extend({}, params, to.redirectTo.params);
          $state.go(to.redirectTo.state, newParams);
        }
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
          // This whole process up here ^^^ and down here vvv is all a bit bogus, should be refactored
          // to be able to have better page tracking with metadata
          var ignore = ['library', 'userOrOrg', 'getStarted', 'orgProfile', 'userProfile'];
          // if base state is not in the ignore list
          if (ignore.indexOf(toStateParts[0]) === -1) {
            var url = $analytics.settings.pageTracking.basePath + $location.url();
            $analytics.pageTrack(url);
          }
        }
        $scope.showSimpleHeader = toState.name.indexOf('getStarted') > -1;
      });

      $rootScope.$on('$stateChangeError', function (event, toState, toParams, fromState, fromParams, error) {
        if (error && _.contains([403, 404], error.status)) {
          event.preventDefault();  // stay in error state
          $scope.errorStatus = error.status;
          $scope.errorParams = toParams;
          $scope.errorState = toState;
        }
      });

      $rootScope.$on('errorImmediately', function (error, params) {
        $scope.errorStatus = error.status || 404;
        $scope.errorParams = params;
      });
    }

    function paramsDiffer(stateName, p1, p2) {
      var paramNames = ($state.get(stateName).url.match(/[:?&]\w+/g) || []).map(function (prop) {
        return prop.slice(1);  // remove leading [:?&]
      });
      return !_.isEqual(_.pick(p1, paramNames), _.pick(p2, paramNames));
    }

    start();

    $rootScope.$on('appStart', start);
  }
]);
