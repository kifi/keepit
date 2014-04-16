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

.run([
  'profileService', '$rootScope', '$window', 'friendService', '$timeout', 'env',
  function (profileService, $rootScope, $window, friendService, $timeout, env) {
    // Initial data loading:

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

    $timeout(function () {
      friendService.getRequests();
    });
  }
])

.controller('AppCtrl', [

  function () {}
]);

'use strict';

angular.module('kifi.alertBanner', [])


.directive('kfAlertBanner', [
  function () {
    return {
      scope: {
        'action': '=',
        'actionText': '@'
      },
      replace: true,
      restrict: 'A',
      transclude: true,
      templateUrl: 'common/directives/alertBanner/alertBanner.tpl.html',
      link: function (/*scope, element, attrs*/) {
      }
    };
  }
]);

'use strict';

angular.module('antiscroll', ['kifi.scrollbar'])

.directive('antiscroll', [
  '$timeout', 'scrollbar',
  function ($timeout, scrollbar) {
    return {
      restrict: 'A',
      transclude: true,
      link: function (scope, element, attrs /*, ctrl, transclude*/ ) {
        var options;
        if (attrs.antiscroll) {
          options = scope.$eval(attrs.antiscroll);
        }
        scope.scroller = element.antiscroll(options).data('antiscroll');

        scope.refreshScroll = function () {
          return $timeout(function () {
            if (scope.scroller) {
              scope.scroller.refresh();
            }
          });
        };

        scope.refreshScroll();

        // http://stackoverflow.com/questions/986937/how-can-i-get-the-browsers-scrollbar-sizes
        scope.width = 'calc(100% + ' + scrollbar.getAntiscrollWidth() + 'px)';

        scope.$on('refreshScroll', scope.refreshScroll);
      },
      template: '<div class="antiscroll-inner" ng-attr-style="width: {{width}}" ng-transclude></div>'
    };
  }
]);

'use strict';

angular.module('kifi.focus', [])

.directive('focusWhen', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      scope: {
        focusWhen: '='
      },
      link: function (scope, element /*, attrs*/ ) {

        function focus() {
          element.focus();
          scope.focusWhen = false;
        }

        scope.$watch('focusWhen', function (val) {
          if (val) {
            $timeout(focus);
          }
        });
      }
    };
  }
])

.directive('kfFocusIf', [
  function () {
    return {
      restrict: 'A',
      scope: {
        focusCond: '='
      },
      link: function (scope, element) {
        scope.$watch('focusCond', function (val) {
          if (val) {
            element.focus();
          }
        });
      }
    };
  }
]);

'use strict';

angular.module('kifi.keepWhoPics', ['kifi.keepWhoService'])

.directive('kfKeepWhoPic', [
  '$window', '$timeout', '$rootElement', '$compile', '$templateCache', 'keepWhoService',
  function ($window, $timeout, $rootElement, $compile, $templateCache, keepWhoService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoPic.tpl.html',
      scope: {
        keeper: '='
      },
      link: function (scope, element) {
        scope.tooltipEnabled = false;
        scope.getPicUrl = keepWhoService.getPicUrl;
        scope.getName = keepWhoService.getName;
        var tooltip = null;
        var timeout = null;

        scope.cancelTimeout = function () {
          $timeout.cancel(timeout);
        };

        scope.$on('$destroy', function () {
          scope.cancelTimeout();
          if (tooltip) {
            tooltip.remove();
          }
        });

        scope.showTooltip = function () {
          if (!tooltip) {
            // Create tooltip
            tooltip = angular.element($templateCache.get('common/directives/keepWho/friendCard.tpl.html'));
            $rootElement.append(tooltip);
            $compile(tooltip)(scope);
          }

          // Set position
          var triangleOffset = 42;
          var triangleWidth = 1;
          var triangle = tooltip.find('.kifi-fr-kcard-tri');
          var left = element.offset().left + element.width() / 2 - triangleOffset;
          var top = element.offset().top - 91;
          var triangleLeft = triangleOffset - triangleWidth;
          if ($window.innerWidth - left - tooltip.width() < 3) {
            left += 2 * triangleOffset - tooltip.width();
            triangleLeft = tooltip.width() - triangleOffset - triangleWidth;
          }
          tooltip.css({left: left + 'px', top: top + 'px', width: tooltip.width() + 'px', visibility: 'hidden'});
          triangle.css({left: triangleLeft});

          timeout = $timeout(function () {
            tooltip.css('visibility', 'visible');
            scope.tooltipEnabled = true;
          }, 500);
        };

        scope.hideTooltip = function () {
          scope.cancelTimeout();
          scope.tooltipEnabled = false;
        };
      }
    };
  }
])

.directive('kfKeepWhoPics', [
  'keepWhoService',
  function (keepWhoService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoPics.tpl.html',
      scope: {
        me: '=',
        keepers: '=',
        keep: '='
      },
      link: function (scope) {
        scope.getPicUrl = keepWhoService.getPicUrl;
        scope.getName = keepWhoService.getName;
        scope.isMyBookmark = scope.keep && scope.keep.isMyBookmark;
      }
    };
  }
]);

'use strict';

angular.module('kifi.keepWhoText', [])

.directive('kfKeepWhoText', [

  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoText.tpl.html',
      scope: {
        keep: '='
      },
      link: function (scope) {
        scope.isPrivate = function () {
          return scope.keep.isPrivate || false;
        };

        scope.hasKeepers = function () {
          var keep = scope.keep;
          return !!(keep.keepers && keep.keepers.length);
        };

        scope.hasOthers = function () {
          var keep = scope.keep;
          return keep.others > 0;
        };

        scope.getFriendText = function () {
          var keepers = scope.keep.keepers,
            len = keepers && keepers.length || 0,
            text;
          if (len === 1) {
            text = '1 friend';
          }
          text = len + ' friends';
          if (!scope.keep.isMyBookmark) {
            return text;
          }
          return '+ ' + text;
        };

        scope.getOthersText = function () {
          var others = scope.keep.others || 0;
          var text;
          if (others === 1) {
            text = '1 other';
          } else {
            text = others + ' others';
          }
          if (scope.keep.isMyBookmark || scope.keep.keepers.length > 0) {
            text = '+ ' + text;
          }
          return text;
        };

        scope.isOnlyMine = function () {
          return !scope.hasKeepers() && !scope.keep.others;
        };
      }
    };
  }
]);

'use strict';

angular.module('nodraginput', [])

.directive('kfNoDragInput', [
  function () {
    return {
      restrict: 'A',
      link: function (scope, element /*, attrs */ ) {

        function disableDragEffect(event) {
          if (event.dataTransfer) {
            event.dataTransfer.dropEffect = 'none';
          }
          event.stopPropagation();
          event.preventDefault();
        }

        element.attr('draggable', 'false');
        element.on('dragstart', function () { return false; });
        element.on('dragenter', disableDragEffect);
        element.on('dragover', disableDragEffect);
        element.on('drop', disableDragEffect);
      }
    };
  }
]);

'use strict';

angular.module('kifi.youtube', [])

.directive('kfYoutube', [

  function () {

    function videoIdToSrc(videoId) {
      return '//www.youtube.com/v/' + videoId +
        '&rel=0&theme=light&showinfo=0&disablekb=1&modestbranding=1&controls=0&hd=1&autohide=1&color=white&iv_load_policy=3';
    }

    function videoEmbed(src) {
      return '<embed src="' + src +
        '" type="application/x-shockwave-flash" allowfullscreen="true" style="width:100%; height: 100%;" allowscriptaccess="always"></embed>';
    }

    return {
      restrict: 'A',
      replace: true,
      scope: {
        videoId: '='
      },
      template: '<div class="kf-youtube"></div>',
      link: function (scope, element) {

        var lastId = null;

        function updateSrc(videoId) {
          if (lastId === videoId) {
            return;
          }
          lastId = videoId;

          if (videoId) {
            element.html(videoEmbed(videoIdToSrc(videoId)));
          }
        }

        updateSrc(scope.videoId);

        scope.$watch('videoId', updateSrc);
      }
    };
  }
]);

'use strict';

angular.module('dom', [])

.value('dom', {
  scrollIntoViewLazy: function (el, padding) {
    var view;
    if (!(el && (view = el.offsetParent))) {
      return;
    }

    var viewTop = view.scrollTop,
      viewHeight = view.clientHeight,
      viewBottom = viewTop + viewHeight,
      elemTop = el.offsetTop,
      elemBottom = elemTop + el.offsetHeight;

    if (elemBottom > viewBottom) {
      view.scrollTop = elemBottom + (padding || 0) - viewHeight;
    }
    else if (elemTop < viewTop) {
      view.scrollTop = elemTop - (padding || 0);
    }
  },

  absOffsets: function (el) {
    var x = 0,
      y = 0;

    while (el) {
      x += el.offsetLeft;
      y += el.offsetTop;
      el = el.offsetParent;
    }

    return {
      x: x,
      y: y
    };
  }
});

'use strict';

angular.module('kifi.modal', [])

.directive('kfModal', [
  '$document',
  function ($document) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        show: '='
      },
      templateUrl: 'common/modal/modal.tpl.html',
      transclude: true,
      controller: ['$scope', function ($scope) {
        var defaultHideAction = null;

        this.setDefaultHideAction = function (action) {
          defaultHideAction = action;
        };

        this.hideModal = function (hideAction) {
          if ($scope.noUserHide && $scope.show) {
            // hide is disabled for user and was not triggered by change of state
            return;
          }
          if (typeof hideAction === 'function') {
            hideAction();
          } else if (defaultHideAction) {
            defaultHideAction();
          }
          $scope.show = false;
        };

        this.show = $scope.show || false;

        $scope.hideModal = this.hideModal;

        function exitModal(evt) {
          if (evt.which === 27) {
            $scope.hideModal(evt);
            $scope.$apply();
          }
        }

        $scope.$watch(function () {
          return $scope.show;
        }, function () {
          this.show = $scope.show || false;
          if ($scope.show) {
            $document.on('keydown', exitModal);
          } else {
            $document.off('keydown', exitModal);
          }
        });

        $scope.$on('$destroy', function () {
          $document.off('keydown', exitModal);
        });
      }],
      link: function (scope, element, attrs) {
        scope.dialogStyle = {};
        scope.backdropStyle = {};
        scope.noUserHide = (attrs.noUserHide !== void 0) || false;

        if (attrs.kfWidth) {
          scope.dialogStyle.width = attrs.kfWidth;
        }
        if (attrs.kfHeight) {
          scope.dialogStyle.height = attrs.kfHeight;
        }

        scope.backdropStyle.opacity = attrs.kfOpacity || 0.3;
        scope.backdropStyle.backgroundColor = attrs.kfBackdropColor || 'rgba(0, 40, 90, 1)';
      }
    };
  }
])

.directive('kfBasicModalContent', [
  '$window',
  function ($window) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        action: '&',
        cancel: '&',
        title: '@'
      },
      templateUrl: 'common/modal/basicModalContent.tpl.html',
      transclude: true,
      require: '^kfModal',
      link: function (scope, element, attrs, kfModalCtrl) {
        scope.title = attrs.title || '';
        scope.singleAction = attrs.singleAction || true;
        scope.actionText = attrs.actionText;
        scope.withCancel = (attrs.withCancel !== void 0) || false;
        scope.withWarning = (attrs.withWarning !== void 0) || false;
        scope.cancelText = attrs.cancelText;
        scope.centered = attrs.centered;
        kfModalCtrl.setDefaultHideAction(scope.cancel);

        scope.hideAndCancel = kfModalCtrl.hideModal;
        scope.hideAndAction = function () {
          kfModalCtrl.hideModal(scope.action);
        };

        var wrap = element.find('.dialog-body-wrap');

        var resizeWindow = _.debounce(function () {
          var winHeight = $window.document.body.clientHeight;
          wrap.css({'max-height': winHeight - 160 + 'px', 'overflow-y': 'auto', 'overflow-x': 'hidden'});
        }, 100);

        scope.$watch(function () {
          return kfModalCtrl.show;
        }, function (show) {
          if (show) {
            resizeWindow();
            $window.addEventListener('resize', resizeWindow);
          } else {
            $window.removeEventListener('resize', resizeWindow);
          }
        });

        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', resizeWindow);
        });
      }
    };
  }
]);

/* global angular */
angular.module('jun.facebook', [])

.provider('$FB', function $FBProvider() {
	'use strict';

	/*
	 * Options
	 */

	var that = this,
		options = {
			// Default option values

			// FB.init params
			// https://developers.facebook.com/docs/javascript/reference/FB.init/
			appId: null,
			cookie: false,
			logging: true,
			status: true,
			xfbml: false,
			authResponse: void 0,
			frictionlessRequests: false,
			hideFlashCallback: null,

			// FB.login options
			// https://developers.facebook.com/docs/reference/javascript/FB.login
			scope: '',
			'enable_profile_selector': false,
			'profile_selector_ids': ''
		};

	function getSetOption(name, val) {
		if (val === void 0) {
			return options[name];
		}
		options[name] = val;
		return that;
	}

	angular.forEach([
		'appId',
		'cookie',
		'logging',
		'status',
		'xfbml',
		'authResponse',
		'frictionlessRequests',
		'hideFlashCallback',
		'scope',
		'enable_profile_selector',
		'profile_selector_ids'
	], function (name) {
		that[name] = angular.bind(that, getSetOption, name);
	});

	this.option = function (name, val) {
		if (typeof name === 'object') {
			angular.extend(options, name);
			return that;
		}
		return getSetOption(name, val);
	};

	var FB, FBPromise, initPromise, $window, $timeout, $q;

	/*
	 * Initialization
	 */

	this.$get = [
		'$window', '$timeout', '$q',
		function ($$window, $$timeout, $$q) {
			$q = $$q;
			$window = $$window;
			$timeout = $$timeout;
			return that;
		}
	];

	/*
	 * Helpers
	 */

	/* jshint validthis: true */
	function handleResponse(response) {
		if (!response || response.error) {
			this.reject(response && response.error || false);
		}
		else {
			this.resolve(response);
		}
	}

	function addCallbackToPromise(deferred, callback) {
		var promise = deferred.promise;
		if (typeof callback === 'function') {
			promise.then(callback);
		}
		return promise;
	}

	/*
	 * Public APIs
	 */
	this.loading = false;
	this.loaded = false;
	this.FB = null;

	this.load = function () {
		if (!FBPromise) {
			var window = $window,
				deferred = $q.defer();

			// https://developers.facebook.com/docs/javascript/quickstart
			window.fbAsyncInit = function () {
				FB = that.FB = window.FB;
				that.loading = false;
				that.loaded = true;

				$timeout(function () {
					deferred.resolve(FB);
				});
			};

			(function (d, s, id) {
				var js, fjs = d.getElementsByTagName(s)[0];
				if (d.getElementById(id)) {
					return;
				}
				js = d.createElement(s);
				js.id = id;
				js.src = '//connect.facebook.net/en_US/all.js';
				fjs.parentNode.insertBefore(js, fjs);
			}(window.document, 'script', 'facebook-jssdk'));

			that.loading = true;

			FBPromise = deferred.promise;
		}
		return FBPromise;
	};

	this.initialized = false;

	this.init = function (params) {
		if (!initPromise) {
			initPromise = that.load().then(function (FB) {
				params = angular.extend({
					appId: options.appId,
					cookie: options.cookie,
					logging: options.logging,
					status: options.status,
					xfbml: options.xfbml,
					authResponse: options.authResponse,
					frictionlessRequests: options.frictionlessRequests,
					hideFlashCallback: options.hideFlashCallback
				}, params);

				if (!params.appId) {
					throw new Error('$FB: appId is not set!');
				}

				FB.init(params);

				that.initialized = true;

				return FB;
			});
		}
		return initPromise;
	};

	this.getLoginStatus = function (callback) {
		// https://developers.facebook.com/docs/reference/javascript/FB.getLoginStatus
		return that.init().then(function (FB) {
			var deferred = $q.defer();

			FB.getLoginStatus(angular.bind(deferred, handleResponse));

			return addCallbackToPromise(deferred, callback);
		});
	};

	this.api = function () {
		var apiArgs = arguments;

		// https://developers.facebook.com/docs/javascript/reference/FB.api
		return that.init().then(function (FB) {
			var deferred = $q.defer(),
				args = Array.prototype.slice.call(apiArgs),
				callback;

			if (typeof args[args.length - 1] === 'function') {
				callback = args.pop();
			}

			args.push(angular.bind(deferred, handleResponse));

			FB.api.apply(FB, args);

			return addCallbackToPromise(deferred, callback);
		});
	};

	this.ui = function () {
		var apiArgs = arguments;

		// https://developers.facebook.com/docs/javascript/reference/FB.ui
		return that.init().then(function (FB) {
			var deferred = $q.defer(),
				args = Array.prototype.slice.call(apiArgs),
				callback;

			if (typeof args[args.length - 1] === 'function') {
				callback = args.pop();
			}

			args.push(angular.bind(deferred, handleResponse));

			FB.ui.apply(FB, args);

			return addCallbackToPromise(deferred, callback);
		});
	};

	this.login = function (callback, opts) {
		// https://developers.facebook.com/docs/reference/javascript/FB.login
		return that.init().then(function (FB) {
			var deferred = $q.defer();

			if (typeof callback !== 'function') {
				callback = null;
				opts = callback;
			}

			function getOpt(name) {
				var val = opts && opts[name];
				return val === void 0 ? options[name] : val;
			}

			FB.login(angular.bind(deferred, handleResponse), {
				scope: getOpt('scope'),
				'enable_profile_selector': getOpt('enable_profile_selector'),
				'profile_selector_ids': getOpt('profile_selector_ids')
			});

			return addCallbackToPromise(deferred, callback);
		});
	};

	this.logout = function (callback) {
		// https://developers.facebook.com/docs/reference/javascript/FB.logout
		return that.getLoginStatus().then(function (response) {
			var deferred = $q.defer();

			if (response.authResponse) {
				FB.logout(angular.bind(deferred, handleResponse));
			}
			else {
				deferred.reject(response);
			}

			return addCallbackToPromise(deferred, callback);
		});

	};

	this.disconnect = function (callback) {
		// http://stackoverflow.com/questions/6634212/remove-the-application-from-a-user-using-graph-api/7741978#7741978
		return that.init().then(function (FB) {
			var deferred = $q.defer();

			FB.api('/me/permissions', 'DELETE', angular.bind(deferred, handleResponse));

			return addCallbackToPromise(deferred, callback);
		});
	};
});

'use strict';

angular.module('kifi.clutch', [])

.factory('Clutch', ['$q', '$timeout', 'util',
  function ($q, $timeout, util) {

    var Clutch = (function () {

      var defaultConfig = {
        remoteError: 'ignore', // used
        cache: false, // todo
        cacheDuration: 30000, // used
        returnPreviousOnExpire: false, // used
        defaultValue: false, // todo
        somethingAboutOffline: true // todo
      };

      var now = Date.now || function () { return new Date().getTime(); };

      function Clutch(func, config) {
        this._config = _.defaults({}, config, defaultConfig);
        this._getter = func;
        this._cache = {};
      }

      // Returns a $q promise that will be resolved with the value
      // If value is cached, the promise is resolved immediately.
      Clutch.prototype.get = function () {
        var key = stringize(arguments);
        var hit = this._cache[key];

        if (!hit) {
          // Never been refreshed.
          return refresh.call(this, key, arguments);
        } else if (!hit.value || hit.activeRequest) {
          // Previous refresh did not finish.
          return hit.q;
        } else if (isExpired(hit.time, this._config.cacheDuration)) {
          // Value exists, but is expired.
          if (this._config.returnPreviousOnExpire) {
            // Trigger refresh, and return previous future
            refresh.call(this, key, arguments);
            return $q.when(hit.value);
          }
          return refresh.call(this, key, arguments);
        }
        return hit.q || $q.when(hit.value);
      };

      Clutch.prototype.contains = function () {
        return !!this._cache[stringize(arguments)];
      };

      Clutch.prototype.refresh = function () {
        var key = stringize(arguments);
        return refresh.call(this, key, arguments);
      };

      Clutch.prototype.age = function () {
        var key = stringize(arguments);
        return key && this._cache[key] && now() - this._cache[key].time;
      };

      Clutch.prototype.isExpired = function () {
        var key = stringize(arguments);
        var prev = key && this._cache[key];
        return prev && isExpired(prev.time, this._config.cacheDuration);
      };

      Clutch.prototype.expire = function () {
        var key = stringize(arguments);
        var prev = this._cache[key];
        delete this._cache[key];
        return prev;
      };

      Clutch.prototype.expireAll = function () {
        this._cache = {};
        return;
      };

      //
      // Private helper functions
      //

      function stringize(args) {
        // todo check if already array
        return JSON.stringify(Array.prototype.slice.call(args));
      }

      function isExpired(hitTime, duration) {
        return now() - hitTime > duration;
      }

      // call by setting this correctly
      function refresh(key, args) {
        var deferred = $q.defer();
        var resultQ = this._getter.apply(this, args);  // todo: check if getter returns a $q promise?

        var obj = this._cache[key] || {};

        // Save the promise so we return the same promise if
        // multiple requests come in before it's resolved.
        obj.q = deferred.promise;
        obj.activeRequest = true;

        this._cache[key] = obj; // todo: needed?
        var that = this;

        resultQ.then(function success(result) {
          obj.time = now();
          obj.activeRequest = false;

          if (!obj.value) {
            // It's never been set before.
            obj.value = result;
            that._cache[key] = obj;
          } else {
            if (obj.value === result) {
              // Nothing to do, getter handled it
              return deferred.resolve(obj.value);
            } else if (angular.isArray(result)) {
              util.replaceArrayInPlace(obj.value, result);
            } else if (angular.isObject(result)) {
              util.replaceObjectInPlace(obj.value, result);
            } else {
              throw new TypeError('Supplied function must return an array/object');
            }
          }
          deferred.resolve(obj.value);
        })['catch'](function (reason) {
          obj.activeRequest = false;
          if (obj.value && that._config.remoteError === 'ignore') {
            deferred.resolve(obj.value);
          } else {
            deferred.reject(reason);
          }
        });
        return deferred.promise;
      }

      return Clutch;

    })();

    return Clutch;

  }
]);

'use strict';

angular.module('kifi.installService', [])

.factory('installService', ['$window', '$log', '$rootScope', '$timeout',
  function ($window, $log, $rootScope, $timeout) {
    var isChrome = $window.chrome && $window.chrome.webstore && $window.chrome.webstore.install;
    var isFirefox = !isChrome && ('MozBoxSizing' in $window.document.documentElement.style) || ($window.navigator.userAgent.indexOf('Firefox') > -1);
    var majorVersion = +($window.navigator.userAgent.match(/(?:Chrome|Firefox)\/(\d+)/) || [null, 999])[1];
    var supported = isChrome && majorVersion >= 26 || isFirefox && majorVersion >= 20;

    if (isChrome && supported) {
      var elem = $window.document.createElement('link');
      elem.rel = 'chrome-webstore-item';
      elem.href = 'https://chrome.google.com/webstore/detail/fpjooibalklfinmkiodaamcckfbcjhin';
      var other = $window.document.getElementsByTagName('link')[0];
      other.parentNode.insertBefore(elem, other);
    }

    var api = {
      triggerInstall: function (onError) {
        if (isChrome && supported) {
          api.installInProgress = true;
          $window.chrome.webstore.install('https://chrome.google.com/webstore/detail/fpjooibalklfinmkiodaamcckfbcjhin', function () {
            api.installed = true;
            api.installInProgress = false;
            api.error = false;
            $rootScope.$digest();
          }, function (e) {
            $log.log(e);
            api.installed = false;
            api.installInProgress = false;
            api.error = true;
            if (onError) {
              onError();
            }
            $rootScope.$digest();
            $timeout(function () {
              api.error = false;
              $rootScope.$digest();
            }, 10000);
          });
        } else if (isFirefox && supported) {
          $window.location.href = '//www.kifi.com/assets/plugins/kifi-beta.xpi';
        } else {
          $window.location.href = '//www.kifi.com/unsupported';
        }
      },
      canInstall: supported,
      installInProgress: false,
      installed: false,
      error: false
    };

    return api;
  }
]);

'use strict';

angular.module('kifi.keepWhoService', [])

.factory('keepWhoService', [
  function () {
    var api = {
      getPicUrl: function (user) {
        if (user && user.id && user.pictureName) {
          return '//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/100/' + user.pictureName;
        }
        return '';
      },

      getName: function (user) {
        if (!user) {
          return '';
        }
        if (user.firstName && user.lastName) {
          return user.firstName + ' ' + user.lastName;
        }
        return user.firstName || user.lastName || '';
      }
    };

    return api;
  }
]);

'use strict';

angular.module('kifi.me', [])

.service('me', function () {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'profileCard/profileCard.tpl.html',
    link: function (scope /*, element, attrs*/ ) {
      scope.firstName = 'Joon Ho';
      scope.lastName = 'Cho';
      scope.description = 'Porting to Angular.js';
    }
  };
});

/**
* inspired by https://github.com/luisfarzati/angulartics/blob/master/src/angulartics-mixpanel.js
*/
(function (angular) {
  'use strict';
  var $window, $log, profileService;

  /**
   * @name kifi.mixpanel
   * Enables analytics support for Mixpanel (http://mixpanel.com)
   */
  angular.module('kifi.mixpanel', ['angulartics'])
  .config(['$analyticsProvider',
    function ($analyticsProvider) {

    var identifiedViewEventQueue = [];
    var userId = null;

    var locations = {
      yourKeeps: /^\/$/,
      yourFriends: /^\/friends$/,
      tagResults: /^\/tag/,
      searchResults: /^\/find/,
      addFriends: /^\/friends\/(invite|find)$/,
      requests: /^\/friends\/requests$/
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
          mixpanel.track('user_viewed_page', {
            type: getLocation(path),
            origin: origin,
            siteVersion: 2,
            userStatus: getUserStatus()
          });
        } finally {
          if (!oldId) {
            mixpanel.identify(oldId);
          }
        }
      } else {
        identifiedViewEventQueue.push(path);
        if (profileService && profileService.me && profileService.me.id) {
          userId = profileService.me.id;
          var toSend = identifiedViewEventQueue.slice();
          identifiedViewEventQueue.length = 0;
          toSend.forEach(function (path) {
            pageTrackForUser(mixpanel, path, origin);
          });
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
          var normalizedPath = getLocation(path);
          var origin = $window.location.origin;
          pageTrackForVisitor(mixpanel, normalizedPath, origin);
          pageTrackForUser(mixpanel, normalizedPath, origin);
        }
      });
    });

    angulartics.waitForVendorApi('mixpanel', 5000, function (/*mixpanel*/) {
      $analyticsProvider.registerEventTrack(function (action, properties) {
        if ($window) {
          var mixpanel = $window.mixpanel;
          $log.log('mixpanelService.eventTrack(' + action + ')', properties);
          mixpanel.track(action, properties);
        }
      });
    });
  }])
  .run([
      'profileService', '$window', '$log',
      function (p, w, l) {
        $window = w;
        profileService = p;
        $log = l;
      }
    ]);

})(angular);

'use strict';

angular.module('kifi.routeService', [])

.factory('routeService', [
  'env',
  function (env) {
    function route(url) {
      return env.xhrBase + url;
    }

    function searchRoute(url) {
      return env.xhrBaseSearch + url;
    }

    function formatPicUrl(userId, pictureName, size) {
      return env.picBase + '/users/' + userId + '/pics/' + (size || 200) + '/' + pictureName;
    }

    return {
      disconnectNetwork: function (network) {
        return env.origin + '/disconnect/' + network;
      },
      linkNetwork: function (network) {
        return env.origin + '/link/' + network;
      },
      refreshNetworks: env.origin + '/friends/invite/refresh', // would love to be more ajax-y
      importStatus: route('/user/import-status'),
      prefs: route('/user/prefs'),
      importGmail: env.origin + '/importContacts', // wtf, why top level route?
      networks: route('/user/networks'),
      profileUrl: route('/user/me'),
      logout: 'https://www.kifi.com/logout',
      emailInfoUrl: route('/user/email'),
      abooksUrl: route('/user/abooks'),
      resendVerificationUrl: route('/user/resend-verification'),
      userPasswordUrl: route('/user/password'),
      formatPicUrl: formatPicUrl,
      removeSingleKeep: function (id) {
        return env.xhrBase + '/keeps/' + id + '/delete';
      },
      removeKeeps: route('/keeps/remove'),
      tagOrdering: route('/collections/ordering'),
      whoToInvite: route('/friends/wti'),
      blockWtiConnection: route('/friends/wti/block'),
      friends: route('/user/friends'),
      friendRequest: function (id) {
        return env.xhrBase + '/user/' + id + '/friend';
      },
      incomingFriendRequests: route('/user/incomingFriendRequests'),
      invite: route('/user/invite'),
      search: searchRoute('/site/search'),
      searchAnalytics: searchRoute('/site/...'),
      socialSearch: function (name, limit) {
        limit = limit || 6;
        return route('/user/connections/all/search?query=' + name + '&limit=' + limit + '&pictureUrl=true');
      }
    };
  }
]);

'use strict';

angular.module('kifi.scrollbar', [])

.factory('scrollbar', [
  '$document',
  function ($document) {

    var width = null;

    function calcScrollBarWidth() {
      // http://stackoverflow.com/questions/986937/how-can-i-get-the-browsers-scrollbar-sizes
      var document = $document[0];

      var inner = document.createElement('p');
      inner.style.width = '100%';
      inner.style.height = '200px';

      var outer = document.createElement('div');
      outer.style.position = 'absolute';
      outer.style.top = '0px';
      outer.style.left = '0px';
      outer.style.visibility = 'hidden';
      outer.style.width = '200px';
      outer.style.height = '150px';
      outer.style.overflow = 'hidden';

      outer.appendChild(inner);

      document.body.appendChild(outer);

      var w1 = inner.offsetWidth;
      outer.style.overflow = 'scroll';

      var w2 = inner.offsetWidth;
      if (w1 === w2) {
        w2 = outer.clientWidth;
      }

      document.body.removeChild(outer);

      return w1 - w2;
    }

    var antiWidth = null,
        $ = angular.element;

    function scrollbarSize() {
      var div = $(
          '<div class="antiscroll-inner" style="width:50px;height:50px;overflow-y:scroll;' +
          'position:absolute;top:-200px;left:-200px;"><div style="height:100px;width:100%"/>' +
          '</div>'
      );

      $('body').append(div);
      var w1 = $(div).innerWidth();
      var w2 = $('div', div).innerWidth();
      $(div).remove();

      return w1 - w2;
    }

    return {
      getWidth: function () {
        if (width == null) {
          width = calcScrollBarWidth();
        }
        return width;
      },
      getAntiscrollWidth: function () {
        if (antiWidth == null) {
          antiWidth = scrollbarSize();
        }
        return antiWidth;
      }
    };
  }
]);

'use strict';

angular.module('kifi.undo', [])

.factory('undoService', [
  '$timeout',
  function ($timeout) {

    var DEFAULT_DURATION = 30000;

    var api = {
      isSet: function () {
        return !!api.callback;
      },
      add: function (message, callback, duration) {
        api.message = message;
        api.callback = callback;

        if (api.promise) {
          $timeout.cancel(api.promise);
        }

        api.promise = $timeout(function () {
          api.promise = null;
          api.clear();
        }, duration == null ? DEFAULT_DURATION : duration);
      },
      clear: function () {
        if (api.promise) {
          $timeout.cancel(api.promise);
        }
        api.message = api.callback = api.promise = null;
      },
      undo: function () {
        var res = null;
        if (api.callback) {
          res = api.callback.call();
        }
        api.clear();

        return res;
      }
    };

    return api;
  }
]);

'use strict';

angular.module('util', [])

.factory('util', [
  '$document', '$window',
  function ($document, $window) {
    return {
      startsWith: function (str, prefix) {
        return str === prefix || str.lastIndexOf(prefix, 0) === 0;
      },
      endsWith: function (str, suffix) {
        return str === suffix || str.indexOf(suffix, str.length - suffix.length) !== -1;
      },
      trimInput: function (input) {
        return input ? input.trim().replace(/\s+/g, ' ') : '';
      },
      validateEmail: function (input) {
        var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/; // jshint ignore:line
        return emailAddrRe.test(input);
      },
      replaceArrayInPlace: function (oldArray, newArray) {
        // empties oldArray, loads newArray values into it, keeping the same reference.
        oldArray = oldArray || [];
        oldArray.length = 0;
        oldArray.push.apply(oldArray, newArray);
      },
      replaceObjectInPlace: function (oldObj, newObj) {
        // empties oldObj, loads newObj key/values into it, keeping the same reference.
        _.forOwn(oldObj || {}, function (num, key) {
          delete oldObj[key];
        });
        _.forOwn(newObj || {}, function (num, key) {
          oldObj[key] = newObj[key];
        });
      },
      /* see http://cvmlrobotics.blogspot.com/2013/03/angularjs-get-element-offset-position.html */
      offset: function (elm) {
        try { return elm.offset(); } catch (e) {}
        var rawDom = elm[0];
        var body = $document.documentElement || $document.body;
        var scrollX = $window.pageXOffset || body.scrollLeft;
        var scrollY = $window.pageYOffset || body.scrollTop;
        var _x = rawDom.getBoundingClientRect().left + scrollX;
        var _y = rawDom.getBoundingClientRect().top + scrollY;
        return { left: _x, top: _y };
      }
    };
  }
])

.directive('postRepeatDirective', [
  '$timeout', '$window',
  function ($timeout, $window) {
    return function (scope) {
      if (scope.$first) {
        if ($window.console && $window.console.time) {
          $window.console.time('postRepeatDirective');
        }
      }

      if (scope.$last) {
        $timeout(function () {
          if ($window.console && $window.console.time) {
            $window.console.time('postRepeatDirective');
            $window.console.timeEnd('postRepeatDirective');
          }
        });
      }
    };
  }
])

.constant('keyIndices', {
  KEY_UP: 38,
  KEY_DOWN: 40,
  KEY_ENTER: 13,
  KEY_ESC: 27,
  KEY_TAB: 9,
  KEY_DEL: 46,
  KEY_F2: 113
});

'use strict';

angular.module('kifi.detail',
	['kifi.keepService', 'kifi.tagService', 'kifi.keepWhoPics', 'kifi.keepWhoText', 'kifi.youtube', 'kifi.profileService', 'kifi.focus']
)

.directive('kfDetail', [
  'keepService', '$filter', '$sce', '$document', 'profileService', '$window',
  function (keepService, $filter, $sce, $document, profileService, $window) {

    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'detail/detail.tpl.html',
      link: function (scope) {
        scope.isSingleKeep = keepService.isSingleKeep;
        scope.getLength = keepService.getSelectedLength;
        scope.isDetailOpen = keepService.isDetailOpen;
        scope.getPreviewed = keepService.getPreviewed;
        scope.getSelected = keepService.getSelected;
        scope.closeDetail = keepService.clearState;
        scope.me = profileService.me;


        scope.$watch(scope.getPreviewed, function (keep) {
          scope.keep = keep;
          scope.refreshScroll();
        });

        scope.getSelectedKeeps = function () {
          if (scope.isSingleKeep()) {
            return [scope.getPreviewed()];
          }
          else {
            return scope.getSelected();
          }
        };

        scope.getPrivateConversationText = function () {
          return scope.keep.conversationCount === 1 ? 'Private Conversation' : 'Private Conversations';
        };

        scope.getTitleText = function () {
          return keepService.getSelectedLength() + ' Keeps selected';
        };

        scope.howKept = null;

        scope.$watch(function () {
          if (scope.isSingleKeep()) {
            if (scope.keep) {
              return scope.keep.isPrivate ? 'private' : 'public';
            }
            return null;
          }

          var selected = scope.getSelected();
          if (_.every(selected, 'isMyBookmark')) {
            return _.every(selected, 'isPrivate') ? 'private' : 'public';
          }
          return null;
        }, function (howKept) {
          scope.howKept = howKept;
        });

        scope.isPrivate = function () {
          return scope.howKept === 'private' && scope.keep && scope.keep.isMyBookmark;
        };

        scope.isPublic = function () {
          return scope.howKept === 'public' && scope.keep && scope.keep.isMyBookmark;
        };

        scope.toggleKeep = function () {
          var keeps = scope.getSelectedKeeps();
          return keepService.toggleKeep(keeps);
        };

        scope.togglePrivate = function () {
          var keeps = scope.getSelectedKeeps();
          return keepService.togglePrivate(keeps);
        };

        scope.refreshScroll = scope.refreshScroll || angular.noop;
        var scrollRefresh = _.throttle(function () {
          scope.refreshScroll();
        }, 150);
        $window.addEventListener('resize', scrollRefresh);

        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', scrollRefresh);
        });

      }
    };
  }
])

.directive('kfTagList', [
  'keepService', 'tagService', '$filter', '$sce', '$document',
  function (keepService, tagService, $filter, $sce, $document) {
    var KEY_UP = 38,
      KEY_DOWN = 40,
      KEY_ENTER = 13,
      KEY_ESC = 27,
      KEY_DEL = 46,
      KEY_F2 = 113;
    var dropdownSuggestionCount = 5;

    return {
      scope: {
        'keep': '=',
        'getSelectedKeeps': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'detail/tagList.tpl.html',
      link: function (scope, element/*, attrs*/ ) {
        scope.data = {};
        scope.data.isClickingInList = false;
        scope.newTagLabel = 'NEW';
        scope.tagFilter = { name: '' };
        scope.tagTypeAheadResults = [];
        scope.shouldGiveFocus = false;

        tagService.fetchAll().then(function (res) {
          scope.allTags = res;
          filterTags(null);
        });

        scope.$watch('keep', function () {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.hideAddTagDropdown();
        });

        scope.getCommonTags = function () {
          var tagLists = _.pluck(scope.getSelectedKeeps(), 'tagList');
          var tagIds = _.map(tagLists, function (tagList) { return _.pluck(tagList, 'id'); });
          var commonTagIds = _.intersection.apply(this, tagIds);
          var tagMap = _.indexBy(_.flatten(tagLists, true), 'id');
          return _.map(commonTagIds, function (tagId) { return tagMap[tagId]; });
        };

        scope.$watch(function () {
          return _.pluck(scope.getSelectedKeeps(), 'tagList');
        }, function () {
          scope.commonTags = scope.getCommonTags();
        }, true);

        function indexOfTag(tag) {
          if (tag) {
            return scope.tagTypeAheadResults.indexOf(tag);
          }
          return -1;
        }

        function filterTags(tagFilterTerm) {
          function keepHasTag(tagId) {
            return scope.keep && scope.allTags && scope.commonTags && !!_.find(scope.commonTags, function (keepTag) {
              return keepTag.id === tagId;
            });
          }
          function allTagsExceptPreexisting() {
            return scope.allTags.filter(function (tag) {
              return !keepHasTag(tag.id);
            }).slice(0, dropdownSuggestionCount);
          }
          function generateDropdownSuggestionCount() {
            var elem = element.find('.page-coll-list');
            if (elem && elem.offset().top) {
              return Math.min(10, Math.max(3, ($document.height() - elem.offset().top) / 24 - 1));
            }
            return dropdownSuggestionCount;
          }
          var splitTf = tagFilterTerm && tagFilterTerm.split(/[\W]+/);
          dropdownSuggestionCount = generateDropdownSuggestionCount();
          if (scope.allTags && tagFilterTerm) {
            var filtered = scope.allTags.filter(function (tag) {
              // for given tagFilterTerm (user search value) and a tag, returns true if
              // every part of the tagFilterTerm exists at the beginning of a part of the tag

              return !keepHasTag(tag.id) && splitTf.every(function (tfTerm) {
                return _.find(tag.name.split(/[\W]+/), function (tagTerm) {
                  return tagTerm.toLowerCase().indexOf(tfTerm.toLowerCase()) === 0;
                });
              });
            });
            scope.tagTypeAheadResults = filtered.slice(0, dropdownSuggestionCount);
          } else if (scope.allTags && !tagFilterTerm) {
            scope.tagTypeAheadResults = allTagsExceptPreexisting();
          }

          if (scope.tagTypeAheadResults.length > 0) {
            scope.highlightFirst();
          }

          scope.tagTypeAheadResults.forEach(function (tag) {
            var safe = tag.name.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            // todo: highlight matching terms
            tag.prettyHtml = $sce.trustAsHtml(safe);
          });
        }

        scope.addTag = function (tag) {
          tagService.addKeepsToTag(tag, scope.getSelectedKeeps());
          scope.tagFilter.name = '';
          return scope.hideAddTagDropdown();
        };

        scope.createAndAddTag = function (keep) {
          tagService.create(scope.tagFilter.name).then(function (tag) {
            scope.addTag(tag, keep);
          });
        };

        scope.isTagHighlighted = function (tag) {
          return scope.highlightedTag === tag;
        };

        // check if the highlighted tag is still in the list
        scope.checkHighlight = function () {
          if (!_.find(scope.tagTypeAheadResults, function (tag) { return scope.highlightedTag === tag; })) {
            scope.highlightTag(null);
          }
        };

        scope.$watch('tagFilter.name', function (tagFilterTerm) {
          filterTags(tagFilterTerm);
          scope.checkHighlight();
        });

        scope.highlightTag = function (tag) {
          return scope.highlightedTag = tag;
        };

        scope.highlightNext = function () {
          var index = indexOfTag(scope.highlightedTag);
          if (index === -1) {
            // no highlight
            // highlight the first
            return scope.highlightFirst();
          }
          if (index === scope.tagTypeAheadResults.length - 1) {
            // last item on the list
            if (scope.isAddTagShown()) {
              // highlight the new tag if available
              return scope.highlightNewSuggestion();
            }
            // the first, otherwise
            return scope.highlightFirst();
          }
          // highlight the next item
          return scope.highlightAt(index + 1);
        };

        scope.highlightPrev = function () {
          var index = indexOfTag(scope.highlightedTag);
          if (index === -1) {
            // no highlight
            // highlight the last
            return scope.highlightLast();
          }
          if (index === 0) {
            // first item on the list
            if (scope.isAddTagShown()) {
              // highlight the new tag if available
              return scope.highlightNewSuggestion();
            }
            // the last, otherwise
            return scope.highlightLast();
          }
          // highlight the previous item
          return scope.highlightAt(index - 1);
        };

        scope.isAddTagShown = function () {
          return scope.tagFilter.name.length > 0 && _.find(scope.tagTypeAheadResults, function (tag) {
            return tag.name === scope.tagFilter.name;
          }) === undefined;
        };

        scope.highlightAt = function (index) {
          if (index == null) {
            return scope.highlightNewSuggestion();
          }
          var tags = scope.tagTypeAheadResults,
            len = tags.length;
          if (!len) {
            return scope.highlightNewSuggestion();
          }

          index = ((index % len) + len) % len;

          var tag = tags[index];
          scope.highlightTag(tag);
          return tag;
        };

        scope.highlightFirst = function () {
          scope.highlightAt(0);
        };

        scope.highlightLast = function () {
          return scope.highlightAt(-1);
        };

        scope.highlightNewSuggestion = function () {
          if (scope.isAddTagShown()) {
            return scope.highlightedTag = null;
          }
          return scope.highlightFirst();
        };

        scope.selectTag = function () {
          if (scope.highlightedTag) {
            return scope.addTag(scope.highlightedTag, scope.keep);
          }
          return scope.createAndAddTag(scope.keep);
        };

        scope.hasTags = function () {
          return scope.commonTags && scope.commonTags.length > 0;
        };

        scope.showAddTagDropdown = function () {
          scope.tagFilter.name = '';
          filterTags(null);
          scope.shouldGiveFocus = true;

          return scope.isAddingTag = true;
        };

        scope.hideAddTagDropdown = function () {
          return scope.isAddingTag = false;
        };

        scope.onKeydown = function (e) {
          switch (e.keyCode) {
          case KEY_UP:
            scope.highlightPrev();
            break;
          case KEY_DOWN:
            scope.highlightNext();
            break;
          case KEY_ENTER:
            scope.selectTag();
            break;
          case KEY_ESC:
            scope.hideAddTagDropdown();
            break;
          case KEY_DEL:
            scope.hideAddTagDropdown();
            break;
          case KEY_F2:
            // scope.rename(scope.highlight);
            break;
          }
        };

        scope.removeTagFromSelectedKeeps = function (tag) {
          tagService.removeKeepsFromTag(tag.id, _.pluck(scope.getSelectedKeeps(), 'id'));
        };

        element.on('mousedown', '.page-coll-opt', function () {
          scope.data.isClickingInList = true;
        }).on('mouseup', '.page-coll-opt', function () {
          scope.data.isClickingInList = false;
        });

        scope.blurTagFilter = function () {
          if (!scope.data.isClickingInList) {
            scope.hideAddTagDropdown();
          }
        };

        scope.addTagLabel = function () {
          if (scope.getSelectedKeeps().length === 1) {
            return 'Add a tag to this keep';
          } else {
            return 'Add a tag to these keeps';
          }
        };

        scope.highlightTag(null);
      }
    };
  }
])

.directive('kfTagSuggestions', [
  '$timeout',
  function ($timeout) {
    return function (scope, element) {
      $timeout(function () {
        var hiddenElement = element.find('.page-coll-opt-hidden');
        var input = element.find('input');
        scope.$watch('tagFilter.name', function (value) {
          var html = value;
          if (scope.isAddTagShown()) {
            html += scope.newTagLabel;
          }
          hiddenElement.html(html);
          var parentWidth = element.parents('.page-coll-list')[0].offsetWidth - 20; // a padding offset
          var width = hiddenElement[0].offsetWidth + 10;
          if (width > parentWidth) {
            width = parentWidth;
          }
          input.css('width', width + 'px');
        });
      });
    };
  }
])

.directive('kfKeepDetail', [
  function () {
    var YOUTUBE_REGEX = /https?:\/\/(?:[0-9A-Z-]+\.)?(?:youtu\.be\/|youtube\.com\S*[^\w\-\s])([\w\-]{11})(?=[^\w\-]|$)[?=&+%\w.-]*/i;

    function isYoutubeVideo(url) {
      return url.indexOf('://www.youtube.com/') > -1 || url.indexOf('youtu.be/') > -1;
    }

    function getYoutubeVideoId(url) {
      var match = url.match(YOUTUBE_REGEX);
      if (match && match.length === 2) {
        return match[1];
      }
      return null;
    }

    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'detail/keepDetail.tpl.html',
      link: function (scope /*, element, attrs*/ ) {

        function testEmbed(keep) {
          if (keep) {
            var url = keep && keep.url;
            if (url && isYoutubeVideo(url)) {
              var vid = getYoutubeVideoId(url);
              if (vid) {
                keep.videoId = vid;
                keep.isEmbed = true;
                return;
              }
            }
            keep.isEmbed = false;
          }
        }

        testEmbed(scope.keep);

        scope.$watch('keep', testEmbed);
      }
    };
  }
]);

'use strict';

angular.module('kifi.friends.friendCard', [])


.directive('kfFriendCard', [
  '$log', 'friendService',
  function ($log, friendService) {
    return {
      scope: {
        'friend': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/friendCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        var friend = scope.friend();
        scope.name = friend.firstName + ' ' + friend.lastName;
        if (friend.firstName[friend.firstName.length - 1] === 's') {
          scope.possesive = friend.firstName + '\'';
        } else {
          scope.possesive = friend.firstName + 's';
        }
        scope.mainImage = '//djty7jcqog9qu.cloudfront.net/users/' + friend.id + '/pics/200/' + friend.pictureName;
        scope.friendCount = friend.friendCount;
        scope.unfriended = friend.unfriended;
        scope.searchFriend = friend.searchFriend;

        scope.unfriend = function () {
          scope.showUnfriendConfirm = true;
        };

        scope.reallyUnfriend = function () {
          friendService.unfriend(friend.id);
        };

        scope.unsearchfriend = function () {
          friendService.unSearchFriend(friend.id);
        };

        scope.researchfriend = function () {
          friendService.reSearchFriend(friend.id);
        };

      }
    };
  }
]);

'use strict';

angular.module('kifi.friends.friendRequestCard', [])


.directive('kfFriendRequestCard', ['$log', 'friendService', function ($log, friendService) {
  return {
    scope: {
      'request': '&'
    },
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/friendRequestCard.tpl.html',
    link: function (scope/*, element, attrs*/) {
      var friend = scope.request();
      scope.name = friend.firstName + ' ' + friend.lastName;
      scope.mainImage = '//djty7jcqog9qu.cloudfront.net/users/' + friend.id + '/pics/200/' + friend.pictureName;

      scope.accept = function () {
        friendService.acceptRequest(friend.id);
      };

      scope.ignore = function () {
        friendService.ignoreRequest(friend.id);
      };
    }
  };
}]);

'use strict';

angular.module('kifi.friendService', [
  'angulartics',
  'util'
])

.factory('friendService', [
  '$http', 'env', '$q', 'routeService', '$analytics', 'Clutch', 'util',
  function ($http, env, $q, routeService, $analytics, Clutch, util) {
    /* Naming convention:
     *  - Kifi Friend is an existing connection on Kifi
     *  - Kifi User is a user of Kifi, may not be a friend.
     */
    var friends = [];
    var requests = [];

    var clutchParams = {
      cacheDuration: 10000
    };

    var kifiFriendsService = new Clutch(function () {
      return $http.get(routeService.friends).then(function (res) {
        friends.length = 0;
        friends.push.apply(friends, _.filter(res.data.friends, function (friend) {
          return !friend.unfriended;
        }));
        return friends;
      });
    }, clutchParams);

    var kifiFriendRequestsService = new Clutch(function () {
      return $http.get(routeService.incomingFriendRequests).then(function (res) {
        util.replaceArrayInPlace(requests, res.data);

        return requests;
      });
    }, clutchParams);

    var api = {
      connectWithKifiUser: function (userId) {
        return userId; // todo!
      },

      getKifiFriends: function () {
        return kifiFriendsService.get();
      },

      getRequests: function () {
        return kifiFriendRequestsService.get();
      },

      friends: friends,

      requests: requests,

      unSearchFriend: function (userExtId) {
        return $http.post(env.xhrBase + '/user/' + userExtId + '/exclude', {}).then(function () {
          kifiFriendsService.expireAll();
          api.getKifiFriends();
          $analytics.eventTrack('user_clicked_page', {
            'action': 'hideFriendInSearch'
          });
        });
      },

      reSearchFriend: function (userExtId) {
        return $http.post(env.xhrBase + '/user/' + userExtId + '/include', {}).then(function () {
          kifiFriendsService.expireAll();
          api.getKifiFriends();
          $analytics.eventTrack('user_clicked_page', {
            'action': 'unHideFriendInSearch'
          });
        });
      },

      acceptRequest: function (extId) {
        return $http.post(env.xhrBase + '/user/' + extId + '/friend', {}).then(function () {
          kifiFriendsService.expireAll();
          kifiFriendRequestsService.expireAll();
          api.getRequests();
          api.getKifiFriends();
          $analytics.eventTrack('user_clicked_page', {
            'action': 'acceptRequest'
          });
        });
      },

      ignoreRequest: function (extId) {
        return $http.post(env.xhrBase + '/user/' + extId + '/ignoreRequest', {}).then(function () {
          kifiFriendsService.expireAll();
          api.getRequests();
          $analytics.eventTrack('user_clicked_page', {
            'action': 'ignoreRequest'
          });
        });
      },

      unfriend: function (userExtId) {
        return $http.post(env.xhrBase + '/user/' + userExtId + '/unfriend', {}).then(function () {
          kifiFriendsService.expireAll();
          api.getKifiFriends();
          $analytics.eventTrack('user_clicked_page', {
            'action': 'unFriend'
          });
        });
      }


    };

    return api;
  }
]);

'use strict';

angular.module('kifi.friends', [
  'util',
  'kifi.social',
  'kifi.profileService',
  'kifi.routeService',
  'kifi.invite'
])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/friends', {
      templateUrl: 'friends/friends.tpl.html',
      resolve: {
        'kifiFriends': ['friendService', function (friendService) {
          return friendService.getKifiFriends();
        }]
      }
    }).when('/friends/requests', {
      redirectTo: '/friends'
    }).when('/friends/requests/:network', {
      redirectTo: '/friends'
    });
  }
])

.controller('FriendsCtrl', [
  '$scope', '$window', 'friendService', 'socialService',
  function ($scope, $window, friendService, socialService) {
    $window.document.title = 'Kifi • Your Friends on Kifi';

    $scope.$watch(socialService.checkIfRefreshingSocialGraph, function (v) {
      $scope.isRefreshingSocialGraph = v;
    });

    socialService.checkIfUpdatingGraphs(1);

    $scope.requests = friendService.requests;
    var requestsCollapsed = true;
    $scope.requestsToShow = 2;
    $scope.requestsToggleText = 'See all requests';
    $scope.toggleRequestExpansion = function () {
      requestsCollapsed = !requestsCollapsed;
      if (requestsCollapsed) {
        $scope.requestsToShow = 2;
        $scope.requestsToggleText = 'See all requests';
      } else {
        $scope.requestsToShow = $scope.requests.length;
        $scope.requestsToggleText = 'See fewer requests';
      }
    };

    $scope.friends = friendService.friends;
    friendService.getKifiFriends();
    friendService.getRequests();
  }
]);


'use strict';

angular.module('kifi.home', ['util', 'kifi.keepService', 'kifi.modal'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/', {
      templateUrl: 'home/home.tpl.html',
      controller: 'HomeCtrl'
    });
  }
])

.controller('HomeCtrl', [
  '$scope', 'tagService', 'keepService', '$q', '$timeout', '$window',
  function ($scope, tagService, keepService, $q, $timeout, $window) {
    keepService.reset();

    $window.document.title = 'Kifi • Your Keeps';

    $scope.keepService = keepService;
    $scope.keeps = keepService.list;

    $scope.toggleSelectAll = keepService.toggleSelectAll;
    $scope.isSelectedAll = keepService.isSelectedAll;

    $scope.isMultiChecked = function () {
      return keepService.getSelectedLength() > 0 && !keepService.isSelectedAll();
    };

    $scope.isCheckEnabled = function () {
      return $scope.keeps.length;
    };

    $scope.hasMore = function () {
      return !keepService.isEnd();
    };

    $scope.mouseoverCheckAll = false;

    $scope.onMouseoverCheckAll = function () {
      $scope.mouseoverCheckAll = true;
    };

    $scope.onMouseoutCheckAll = function () {
      $scope.mouseoverCheckAll = false;
    };

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      }

      var subtitle = keepService.getSubtitle($scope.mouseoverCheckAll);
      if (subtitle) {
        return subtitle;
      }

      var numShown = $scope.keeps.length;
      switch (numShown) {
      case 0:
        return 'You have no Keeps';
      case 1:
        return 'Showing your only Keep';
      case 2:
        return 'Showing both of your Keeps';
      default:
        if (keepService.isEnd()) {
          return 'Showing all ' + numShown + ' of your Keeps';
        }
        return 'Showing your ' + numShown + ' latest Keeps';
      }
    };

    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return $q.when([]);
      }

      $scope.loading = true;

      return keepService.getList().then(function (list) {
        $scope.loading = false;

        if (keepService.isEnd()) {
          $scope.scrollDisabled = true;
        }

        return list;
      });
    };

    $scope.getNextKeeps();
  }
]);

'use strict';

angular.module('kifi.invite.connectionCard', ['angularMoment'])


.directive('kfConnectionCard', ['$window', '$http', 'routeService', 'inviteService', function ($window, $http, routeService, inviteService) {
  return {
    scope: {
      'friend': '&',
      'refreshScroll': '=',
      'showGenericInviteError': '=',
      'showLinkedinTokenExpiredModal': '=',
      'showLinkedinHitRateLimitModal': '='
    },
    replace: true,
    restrict: 'A',
    templateUrl: 'invite/connectionCard.tpl.html',
    link: function (scope/*, element, attrs*/) {
      var friend = scope.friend();
      var network = friend.fullSocialId.split('/')[0];
      var inNetworkId = friend.fullSocialId.split('/')[1];
      var invited = (friend.lastInvitedAt != null);

      if (friend.pictureUrl != null) {
        scope.mainImage = friend.pictureUrl;
      } else if (network === 'email') {
        scope.mainImage = '/img/email-icon.png';
      } else {
        scope.mainImage = 'https://www.kifi.com/assets/img/ghost.100.png';
      }

      scope.mainLabel = friend.name;
      scope.hidden = false;

      scope.facebook = network === 'facebook';
      scope.linkedin = network === 'linkedin';
      scope.email    = network === 'email';

      scope.action = function () {
        inviteService.invite(network, inNetworkId).then(function () {
          scope.invited = true;
          scope.actionText = 'Resend';
          var inviteText = 'Invited just now';
          if (network === 'email') {
            scope.byline = inNetworkId;
            scope.byline2 = inviteText;
          } else {
            scope.byline = inviteText;
          }
        }, function (err) {
          if (err === 'token_expired') {
            scope.showLinkedinTokenExpiredModal = true;
          } else if (err === 'hit_rate_limit_reached') {
            scope.showLinkedinHitRateLimitModal = true;
          } else {
            scope.showGenericInviteError = true;
          }
        });
      };
      scope.closeAction = function () {
        scope.hidden = true;
        var data = { 'fullSocialId' : friend.fullSocialId };
        $http.post(routeService.blockWtiConnection, data);
      };
      if (invited) {
        scope.invited = true;
        scope.actionText = 'Resend';
        var inviteText = 'Invited ' + $window.moment(new Date(friend.lastInvitedAt)).fromNow();
        if (network === 'email') {
          scope.byline = inNetworkId;
          scope.byline2 = inviteText;
        } else {
          scope.byline = inviteText;
        }
      } else {
        scope.invited = false;
        scope.byline = network === 'email' ? inNetworkId : network.charAt(0).toUpperCase() + network.slice(1);
        scope.actionText = 'Add';
      }
      scope.refreshScroll();
    }
  };
}]);

'use strict';

angular.module('kifi.invite', [
  'util',
  'kifi.profileService',
  'kifi.routeService',
  'jun.facebook',
  'kifi.inviteService',
  'kifi.social',
  'kifi.modal'
])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/invite', {
      templateUrl: 'invite/invite.tpl.html'
    }).when('/friends/invite', {
      redirectTo: '/invite'
    });
  }
])

.controller('InviteCtrl', [
  '$scope', '$http', '$rootScope', 'profileService', 'routeService', '$window', 'wtiService', 'socialService',
  function ($scope, $http, $rootScope, profileService, routeService, $window, wtiService, socialService) {
    $window.document.title = 'Kifi • Invite your friends';

    $scope.$watch(socialService.checkIfRefreshingSocialGraph, function (v) {
      $scope.isRefreshingSocialGraph = v;
    });

    socialService.checkIfUpdatingGraphs(2);

    $scope.whoToInvite = wtiService.list;

    $scope.wtiLoaded = false;
    $scope.$watch(function () {
      return wtiService.list.length || !wtiService.hasMore();
    }, function (res) {
      if (res) {
        $scope.wtiLoaded = true;
      }
    });

    $scope.wtiScrollDistance = '100%';
    $scope.isWTIScrollDisabled = function () {
      return !wtiService.hasMore();
    };
    $scope.wtiScrollNext = wtiService.getMore;

    $scope.showAddNetworksModal = function () {
      $rootScope.$emit('showGlobalModal', 'addNetworks');
    };
  }
])

.directive('kfSocialInviteWell', [
  'socialService', '$rootScope',
  function (socialService, $rootScope) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/inviteWell.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.networks = socialService.networks;
        scope.$watch(function () {
          return socialService.networks.length;
        }, function (networksLength) {
          scope.networkText = networksLength === 1 ? '1 network connected' : networksLength + ' networks connected';
        });


        scope.data = scope.data || {};

        scope.showAddNetworks = function () {
          $rootScope.$emit('showGlobalModal', 'addNetworks');
        };

        socialService.refresh();
      }
    };
  }
])

.directive('kfSocialInviteSearch', [
  'inviteService', '$document', '$log', 'socialService', '$timeout', '$rootScope',
  function (inviteService, $document, $log, $socialService, $timeout, $rootScope) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/inviteSearch.tpl.html',
      link: function (scope, element/*, attrs*/) {
        scope.search = {};
        scope.search.showDropdown = false;
        scope.data = scope.data || {};

        scope.results = [];
        scope.selected = inviteService.socialSelected;

        scope.change = _.debounce(function () { // todo: integrate service-wide debounce into Clutch, remove me
          inviteService.socialSearch(scope.search.name).then(function (res) {

            var set = _.clone(res);

            var socialConns = _.filter(res, function (result) {
              return result.network && result.network.indexOf('fortytwo') === -1;
            }).length;

            if (scope.search.name.length > 2 && (res.length < 3 || socialConns < 3)) {
              set.push({
                custom: 'cant_find'
              });
            }

            scope.results = set;

            if (!set || set.length === 0) {
              scope.search.showDropdown = false;
            } else {
              scope.search.showDropdown = true;
            }
          });
        }, 200);

        function clickOutside(e) {
          if (scope.search.showDropdown && !element.find(e.target)[0]) { // click was outside of dropdown
            scope.$apply(function () {
              scope.search.name = '';
              scope.search.showDropdown = false;
            });
          }
        }

        var ignoreClick = {};

        scope.invite = function (result, $event) {
          $log.log('this person:', result);
          if (ignoreClick[result.socialId]) {
            return;
          }
          ignoreClick[result.socialId] = true;

          var $elem = angular.element($event.target);
          $elem.text('Sending');
          $elem.parent().removeClass('clickable');
          if (result.networkType === 'fortytwo' || result.networkType === 'fortytwoNF') {
            // Existing user, friend request
            inviteService.friendRequest(result.socialId).then(function () {
              $elem.text('Sent!');
              $timeout(function () {
                delete ignoreClick[result.socialId];
                $elem.text('Resend');
                $elem.parent().addClass('clickable');
              }, 4000);
              inviteService.expireSocialSearch();
            }, function (err) {
              $log.log('err:', err, result);
              delete ignoreClick[result.socialId];
              $elem.text('Error. Retry?');
              $elem.parent().addClass('clickable');
              inviteService.expireSocialSearch();
            });
          } else {
            // Request to external person
            inviteService.invite(result.networkType, result.socialId).then(function () {
              $elem.text('Sent!');
              $timeout(function () {
                delete ignoreClick[result.socialId];
                $elem.text('Resend');
                $elem.parent().addClass('clickable');
              }, 4000);
              inviteService.expireSocialSearch();
            }, function (err) {
              $log.log('err:', err, result);
              delete ignoreClick[result.socialId];
              $elem.text('Error. Retry?');
              $elem.parent().addClass('clickable');
              inviteService.expireSocialSearch();
            });
          }
        };

        scope.$on('$destroy', function () {
          $document.off('click', clickOutside);
        });

        $document.on('click', clickOutside);

        scope.refreshFriends = function () {
          scope.data.showCantFindModal = false;
          $socialService.refreshSocialGraph();
        };

        scope.connectNetworks = function () {
          scope.data.showCantFindModal = false;
          $rootScope.$emit('showGlobalModal', 'addNetworks');
        };

        scope.hasNetworks = function () {
          return !!$socialService.networks.length;
        };

      }
    };
  }
]);

'use strict';

angular.module('kifi.inviteService', [
  'util',
  'kifi.clutch',
  'angulartics'
])

.factory('inviteService', [
  '$http', 'env', '$q', 'routeService', 'util', 'Clutch', '$window', '$log', '$analytics', '$FB',
  function ($http, env, $q, routeService, util, Clutch, $window, $log, $analytics, $FB) {
    /* Naming convention:
     *  - Kifi Friend is an existing connection on Kifi
     *  - Kifi User is a user of Kifi, may not be a friend.
     */

    $FB.getLoginStatus(); //This causes the Facebook SDK to initialize properly. Don't remove!

    var inviteList = [], // used for typeahead dropdown for invite search
        selected,
        lastSearch;

    var friendlyNetworks = {'facebook': 'Facebook', 'linkedin': 'LinkedIn'};
    var socialSearchService = new Clutch(function (name) {
      if (!name || !name.trim()) {
        return $q.when([]);
      }
      return $http.get(routeService.socialSearch(name)).then(function (res) {
        var results = res.data;
        _.forEach(results, augmentSocialResult);
        $analytics.eventTrack('user_clicked_page', {
          'action': 'searchContacts'
        });
        return results;
      });
    });

    var customEmail = {
      custom: 'email',
      iconStyle: 'kf-email-icon-micro',
      networkType: 'email',
      status: ''
    };

    function augmentSocialResult(result) {
      result.socialId = result.value.split('/').splice(1).join('');
      var trimmedLabel = result.label.trim();
      result.label = trimmedLabel ? trimmedLabel : result.socialId;
      result.network = result.networkType === 'email' ? result.socialId : friendlyNetworks[result.networkType] || result.networkType;
      result.iconStyle = 'kf-' + result.networkType + '-icon-micro';
      if (result.networkType === 'fortytwo' || result.networkType === 'fortytwoNF') {
        result.image = routeService.formatPicUrl(result.socialId, result.image);
      }
      if (result.status === 'invited') {
        var sendText = $window.moment(new Date(result.inviteLastSentAt)).fromNow();
        result.inviteText = sendText;
      }
      return result;
    }

    function populateWithCustomEmail(name, results) {
      if (name.indexOf('@') > 0) {
        var last = results[results.length - 1];
        if (last && last.custom) {
          if (last.label === name) {
            return;
          } else {
            results.pop();
          }
        }
        // They're typing in an email address
        var resultInside = _.find(results, function (elem) {
          return elem.networkType === 'email' && elem.value.split('/').splice(1).join('') === name;
        });
        if (!resultInside) {
          customEmail.socialId = name;
          customEmail.label = name;
          customEmail.network = name;
          customEmail.value = 'email/' + name;
          results.push(augmentSocialResult(customEmail));
        }
      }

    }

    var api = {

      socialSearch: function (name) {
        lastSearch = name;
        populateWithCustomEmail(name, inviteList);

        return socialSearchService.get(name).then(function (results) {

          populateWithCustomEmail(lastSearch, results);
          util.replaceArrayInPlace(inviteList, results);

          // find which was selected, if not:
          if (results.length === 0) {
            selected = null;
          } else {
            selected = results[0].value;
          }
          return results;
        });
      },

      expireSocialSearch: function () {
        socialSearchService.expireAll();
      },

      inviteList: inviteList,

      socialSelected: selected,

      invite: function (platform, identifier) {

        socialSearchService.expireAll();

        var deferred = $q.defer();

        function doInvite() {
          $http.post(routeService.invite, {
            id: platform + '/' + identifier
          }).then(function (res) {
            $analytics.eventTrack('user_clicked_page', {
              'action': 'inviteFriend',
              'platform': platform
            });
            if (res.data.url && platform === 'facebook') {
              $FB.ui({
                method: 'send',
                link: $window.unescape(res.data.url),
                to: identifier
              });
              deferred.resolve('');
            } else if (res.data.error) {
              $log.log(res.data.error);
              if (res.data.error.code === 'linkedin_error_{401}') {
                deferred.reject('token_expired'); // technically the token could also just be invalid, but we don't get that info from the backend
              } else if (res.data.error.code === 'linkedin_error_{403}') {
                deferred.reject('hit_rate_limit_reached');
              } else {
                deferred.reject('generic_error');
              }
            } else {
              deferred.resolve('');
            }
          }, function (err) {
            $log.log(err);
            throw err;
          });
        }

        //login if needed
        if (platform === 'facebook') {
          if ($FB.FB.getAuthResponse()) {
            doInvite();
          } else {
            $FB.FB.login(function (response) {
              if (response.authResponse) {
                doInvite();
              }
              //else user cancelled login. Do nothing further.
            });
          }
        } else {
          if (platform === 'fortytwoNF') {
            platform = 'fortytwo';
          }
          doInvite();
        }

        return deferred.promise;

      },

      friendRequest: function (id) {
        return $http.post(routeService.friendRequest(id)).then(function (res) {
          return res.data;
        });
      }

    };

    return api;
  }
]);

'use strict';

angular.module('kifi.invite.wtiService', ['kifi.clutch'])

.factory('wtiService', [
  '$http', 'routeService', 'Clutch', '$q',
  function ($http, routeService, Clutch, $q) {
    var list = [];
    var more = true;
    var page = 0;

    var wtiRemoteService = new Clutch(function (pageToGet) {
      return $http.get(routeService.whoToInvite + '?page=' + pageToGet).then(function (res) {
        if (res.data.length === 0) {
          more = false;
        } else {
          page++;
          list.push.apply(list, res.data);
        }
        return res.data;
      });
    });

    var api = {
      getMore: function () {
        return wtiRemoteService.get(page);
      },
      hasMore: function () {
        return more;
      },
      loadInitial: function () {
        list.length = 0;
        page = 0;
        more = true;
        wtiRemoteService.expireAll();

        var first = api.getMore();
        first.then(function () {
          return api.getMore();
        });

        if (list.length > 0) {
          return $q.when(list);
        }
        return first;
      },
      list: list
    };

    return api;
  }
]);

'use strict';

angular.module('kifi.addKeeps', ['kifi.profileService'])

.directive('kfAddKeepsModal', [
  function () {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'keep/addKeepsModal.tpl.html',
      scope: {
        'data': '='
      },
      link: function (/*scope, element, attrs*/ ) {



      }
    };
  }
]);

'use strict';

angular.module('kifi.keep', ['kifi.keepWhoPics', 'kifi.keepWhoText', 'kifi.tagService'])

.controller('KeepCtrl', [
  '$scope',
  function ($scope) {
    $scope.isMyBookmark = function (keep) {
      return keep.isMyBookmark || false;
    };

    $scope.isPrivate = function (keep) {
      return keep.isPrivate || false;
    };

    $scope.isExampleTag = function (tag) {
      return (tag && tag.name && tag.name.toLowerCase()) === 'example keep';
    };

    function hasExampleTag(tags) {
      if (tags && tags.length) {
        for (var i = 0, l = tags.length; i < l; i++) {
          if ($scope.isExampleTag(tags[i])) {
            return true;
          }
        }
      }
      return false;
    }

    $scope.isExample = function (keep) {
      if (keep.isExample == null) {
        keep.isExample = hasExampleTag($scope.getTags());
      }
      return keep.isExample;
    };
  }
])

.directive('kfKeep', [
  '$document', '$rootElement', 'tagService', 'util',
  function ($document, $rootElement, tagService, util) {
    return {
      restrict: 'A',
      scope: {
        keep: '=',
        me: '=',
        toggleSelect: '&',
        isPreviewed: '&',
        isSelected: '&',
        clickAction: '&',
        dragKeeps: '&',
        stopDraggingKeeps: '&'
      },
      controller: 'KeepCtrl',
      replace: true,
      templateUrl: 'keep/keep.tpl.html',
      link: function (scope, element /*, attrs*/ ) {
        scope.getTags = function () {
          return scope.keep.tagList;
        };

        var aUrlParser = $document[0].createElement('a');
        var secLevDomainRe = /[^.\/]+(?:\.[^.\/]{1,3})?\.[^.\/]+$/;
        var fileNameRe = /[^\/]+?(?=(?:\.[a-zA-Z0-9]{1,6}|\/|)$)/;
        var fileNameToSpaceRe = /[\/._-]/g;

        function formatTitleFromUrl(url, matches) {
          aUrlParser.href = url;

          var domain = aUrlParser.hostname;
          var domainIdx = url.indexOf(domain);
          var domainMatch = domain.match(secLevDomainRe);
          if (domainMatch) {
            domainIdx += domainMatch.index;
            domain = domainMatch[0];
          }

          var fileName = aUrlParser.pathname;
          var fileNameIdx = url.indexOf(fileName, domainIdx + domain.length);
          var fileNameMatch = fileName.match(fileNameRe);
          if (fileNameMatch) {
            fileNameIdx += fileNameMatch.index;
            fileName = fileNameMatch[0];
          }
          fileName = fileName.replace(fileNameToSpaceRe, ' ').trimRight();

          for (var i = matches && matches.length; i--;) {
            var match = matches[i];
            var start = match[0],
              len = match[1];
            if (start >= fileNameIdx && start < fileNameIdx + fileName.length) {
              fileName = bolded(fileName, start - fileNameIdx, len);
            }
            else if (start >= domainIdx && start < domainIdx + domain.length) {
              domain = bolded(domain, start - domainIdx, len);
            }
          }
          fileName = fileName.trimLeft();

          return domain + (fileName ? ' · ' + fileName : '');
        }

        function bolded(text, start, len) {
          return text.substr(0, start) + '<b>' + text.substr(start, len) + '</b>' + text.substr(start + len);
        }

        function toTitleHtml(keep) {
          return keep.title || formatTitleFromUrl(keep.url);
        }

        var strippedSchemeRe = /^https?:\/\//;
        var domainTrailingSlashRe = /^([^\/]*)\/$/;

        function formatDesc(url, matches) {
          var strippedSchemeLen = (url.match(strippedSchemeRe) || [''])[0].length;
          url = url.substr(strippedSchemeLen).replace(domainTrailingSlashRe, '$1');
          for (var i = matches && matches.length; i--;) {
            matches[i][0] -= strippedSchemeLen;
          }
          return boldSearchTerms(url, matches);
        }

        function boldSearchTerms(text, matches) {
          for (var i = matches && matches.length; i--;) {
            var match = matches[i];
            var start = match[0];
            if (start >= 0) {
              text = bolded(text, start, match[1]);
            }
          }
          return text;
        }

        function updateTitleHtml() {
          scope.keep.titleHtml = toTitleHtml(scope.keep);
        }

        function updateDescHtml() {
          scope.keep.descHtml = formatDesc(scope.keep.url);
        }

        updateTitleHtml();
        updateDescHtml();

        // Really weird hack to fix a ng-class bug
        // In certain cases, ng-class is not setting DOM classes correctly.
        // Reproduction: select several keeps, preview one of the keeps,
        // unselect it. isSelected(keep) is false, but it'll still appear
        // as checked.
        scope.$watchCollection(function () {
          return {
            'mine': scope.isMyBookmark(scope.keep),
            'example': scope.isExample(scope.keep),
            'private': scope.isPrivate(scope.keep),
            'detailed': scope.isPreviewed({keep: scope.keep}),
            'selected': !!scope.isSelected({keep: scope.keep})
          };
        }, function (cur) {
          _.forOwn(cur, function (value, key) {
            if (value && !element.hasClass(key)) {
              element.addClass(key);
            } else if (!value && element.hasClass(key)) {
              element.removeClass(key);
            }
          });
        });

        scope.$watch('keep.title', function () {
          updateTitleHtml();
        });

        scope.$watch('keep.url', function () {
          updateTitleHtml();
          updateDescHtml();
        });

        scope.getTitle = function () {
          var keep = scope.keep;
          return keep.title || keep.url;
        };

        scope.getName = function (user) {
          return [user.firstName, user.firstName].filter(function (n) {
            return !!n;
          }).join(' ');
        };

        scope.hasKeepers = function () {
          var keep = scope.keep;
          return !!(keep.keepers && keep.keepers.length);
        };

        scope.showOthers = function () {
          return !scope.hasKeepers() && !! scope.keep.others;
        };

        scope.onCheck = function (e) {
          // needed to prevent previewing
          e.stopPropagation();
          return scope.toggleSelect();
        };

        var dragMask = element.find('.kf-drag-mask');
        scope.isDragTarget = false;

        scope.onTagDrop = function (tag) {
          tagService.addKeepToTag(tag, scope.keep);
          scope.isDragTarget = false;
        };

        dragMask.on('dragenter', function () {
          scope.$apply(function () { scope.isDragTarget = true; });
        });

        dragMask.on('dragleave', function () {
          scope.$apply(function () { scope.isDragTarget = false; });
        });

        scope.isDragging = false;
        var mouseX, mouseY;
        element.on('mousemove', function (e) {
          mouseX = e.pageX - util.offset(element).left;
          mouseY = e.pageY - util.offset(element).top;
        });
        element.on('dragstart', function (e) {
          scope.$apply(function () {
            element.addClass('kf-dragged');
            scope.dragKeeps({keep: scope.keep, event: e, mouseX: mouseX, mouseY: mouseY});
            scope.isDragging = true;
          });
        });
        element.on('dragend', function () {
          scope.$apply(function () {
            element.removeClass('kf-dragged');
            scope.stopDraggingKeeps();
            scope.isDragging = false;
          });
        });
      }
    };
  }
]);

'use strict';

angular.module('kifi.keepService', [
  'kifi.undo',
  'kifi.clutch',
  'angulartics'
])

.factory('keepService', [
  '$http', 'env', '$q', '$timeout', '$document', '$rootScope', 'undoService', '$log', 'Clutch', '$analytics', 'routeService',
  function ($http, env, $q, $timeout, $document, $rootScope, undoService, $log, Clutch, $analytics, routeService) {

    var list = [],
      selected = {},
      before = null,
      end = false,
      previewed = null,
      selectedIdx,
      limit = 30,
      isDetailOpen = false,
      singleKeepBeingPreviewed = false,
      previewUrls = {},
      doc = $document[0],
      screenshotDebouncePromise = false;

    $rootScope.$on('tags.remove', function (tagId) {
      _.forEach(list, function (keep) {
        if (keep.tagList) {
          keep.tagList = keep.tagList.filter(function (tag) {
            if (tag.id === tagId) {
              if (!keep.removedTagList) {
                keep.removedTagList = [];
              }
              keep.removedTagList.push(tag);
              return false;
            }
            return true;
          });
        }
      });
    });

    $rootScope.$on('tags.unremove', function (tagId) {
      _.forEach(list, function (keep) {
        if (keep.removedTagList) {
          keep.removedTagList.filter(function (tag) {
            if (tag.id === tagId) {
              if (!keep.tagList) {
                keep.tagList = [];
              }
              keep.tagList.push(tag);
              return false;
            }
            return true;
          });
        }
      });
    });

    $rootScope.$on('tags.removeFromKeep', function (e, data) {
      var tagId = data.tagId,
          keepId = data.keepId;
      _.forEach(list, function (keep) {
        if (keep.id === keepId && keep.tagList) {
          keep.tagList = keep.tagList.filter(function (tag) {
            return tag.id !== tagId;
          });
        }
      });
    });

    $rootScope.$on('tags.addToKeep', function (e, data) {
      var tag = data.tag,
          keepId = data.keep.id;
      _.forEach(list, function (keep) {
        keep.isMyBookmark = true;
        if (keep.id === keepId && keep.tagList) {
          var isAlreadyThere = _.find(keep.tagList, function (existingTag) {
            return existingTag.id === tag.id;
          });
          if (!isAlreadyThere) {
            keep.tagList.push(tag);
          }
        }
      });
    });

    function fetchScreenshots(keeps) {
      if (keeps && keeps.length) {
        api.fetchScreenshotUrls(keeps).then(function (urls) {
          _.forEach(keeps, function (keep) {
            keep.screenshot = urls[keep.url];
          });
        });
      }
    }

    function lookupScreenshotUrls(keeps) {
      if (keeps && keeps.length) {
        var url = env.xhrBase + '/keeps/screenshot',
          data = {
            urls: _.pluck(keeps, 'url')
          };

        $log.log('keepService.lookupScreenshotUrls()', data);

        return $http.post(url, data).then(function (res) {
          $timeout(function () {
            api.prefetchImages(res.data.urls);
          });
          return res.data.urls;
        });
      }
      return $q.when(keeps || []);
    }

    function processHit(hit) {
      _.extend(hit, hit.bookmark);

      hit.keepers = hit.users;
      hit.others = hit.count - hit.users.length - (hit.isMyBookmark && !hit.isPrivate ? 1 : 0);
    }

    function keepIdx(keep) {
      if (!keep) {
        return -1;
      }
      var givenId = keep.id;

      for (var i = 0, l = list.length; i < l; i++) {
        if (givenId && list[i].id === givenId) {
          return i;
        } else if (!givenId && list[i] === keep) {
          // No id, do object comparison. todo: have a better way to track keeps when they have no ids.
          return i;
        }
      }
      return -1;
    }

    function expiredConversationCount(keep) {
      if (!keep.conversationUpdatedAt) {
        return true;
      }
      var diff = new Date().getTime() - keep.conversationUpdatedAt.getTime();
      return diff / 1000 > 15; // conversation count is older than 15 seconds
    }



    var keepList = new Clutch(function (url, config) {
      $log.log('keepService.getList()', config && config.params);

      return $http.get(url, config).then(function (res) {
        var data = res.data,
          keeps = data.keeps || [];

        _.forEach(keeps, function (keep) {
          keep.isMyBookmark = true;
        });

        fetchScreenshots(keeps);

        return { keeps: keeps, before: data.before };
      });
    });


    var api = {
      list: list,

      totalKeepCount: 0,

      isDetailOpen: function () {
        return isDetailOpen;
      },

      isSingleKeep: function () {
        return singleKeepBeingPreviewed;
      },

      getPreviewed: function () {
        return previewed || null;
      },

      isPreviewed: function (keep) {
        return !!previewed && previewed === keep;
      },

      getHighlighted: function () {
        return list[selectedIdx];
      },

      preview: function (keep) {
        if (keep == null) {
          api.clearState();
        } else {
          singleKeepBeingPreviewed = true;
          isDetailOpen = true;
        }
        var detectedIdx = keepIdx(keep);
        selectedIdx = detectedIdx >= 0 ? detectedIdx : selectedIdx || 0;
        previewed = keep;
        api.getChatter(previewed);

        $analytics.eventTrack('user_clicked_page', {
          'action': 'preview',
          'selectedIdx': selectedIdx
        });

        return keep;
      },

      togglePreview: function (keep) {
        if (api.isPreviewed(keep) && _.size(selected) > 1) {
          previewed = null;
          isDetailOpen = true;
          singleKeepBeingPreviewed = false;
          return null;
        }
        else if (api.isPreviewed(keep)) {
          return api.clearState();
        }
        return api.preview(keep);
      },

      previewNext: _.throttle(function () {
        selectedIdx = selectedIdx || 0;
        var toPreview;
        if (list.length - 1 > selectedIdx) {
          toPreview = list[selectedIdx + 1];
          selectedIdx++;
        } else {
          toPreview = list[0];
          selectedIdx = 0;
        }
        api.togglePreview(toPreview);
      }, 150),

      previewPrev: _.throttle(function () {
        selectedIdx = selectedIdx || 0;
        var toPreview;
        if (selectedIdx > 0) {
          toPreview = list[selectedIdx - 1];
          selectedIdx--;
        } else {
          toPreview = list[0];
          selectedIdx = 0;
        }
        if (!api.isPreviewed(toPreview)) {
          api.togglePreview(toPreview);
        }
      }, 150),

      isSelected: function (keep) {
        return keep && keep.id && !!selected[keep.id];
      },

      select: function (keep) {
        var id = keep.id;
        if (id) {
          isDetailOpen = true;
          selected[id] = keep;
          if (_.size(selected) === 1) {
            api.preview(keep);
          }
          else {
            previewed = null;
            singleKeepBeingPreviewed = false;
          }
          selectedIdx = keepIdx(keep);
          return true;
        }
        return false;
      },

      unselect: function (keep) {
        var id = keep.id;
        if (id) {
          delete selected[id];
          var countSelected = _.size(selected);
          if (countSelected === 0 && isDetailOpen === true) {
            api.preview(keep);
            selectedIdx = keepIdx(keep);
          }
          else if (countSelected === 1 && isDetailOpen === true) {
            var first = api.getFirstSelected();
            selectedIdx = keepIdx(first);
            api.preview(first);
          }
          else {
            previewed = null;
            singleKeepBeingPreviewed = false;
          }
          return true;
        }
        return false;
      },

      toggleSelect: function (keep) {
        if (keep === undefined) {
          if (previewed) {
            return api.toggleSelect(previewed);
          } else if (selectedIdx >= 0) {
            return api.toggleSelect(list[selectedIdx]);
          }
        } else if (api.isSelected(keep)) {
          return api.unselect(keep);
        } else if (keep) {
          return api.select(keep);
        }
      },

      getFirstSelected: function () {
        return _.values(selected)[0];
      },

      getSelectedLength: function () {
        return _.keys(selected).length;
      },

      getSelected: function () {
        return list.filter(function (keep) {
          return keep.id in selected;
        });
      },

      selectAll: function () {
        selected = _.reduce(list, function (map, keep) {
          map[keep.id] = true;
          return map;
        }, {});
        if (list.length === 0) {
          api.clearState();
        }
        else if (list.length === 1) {
          api.preview(list[0]);
        }
        else {
          previewed = null;
          isDetailOpen = true;
          singleKeepBeingPreviewed = false;
        }
      },

      unselectAll: function () {
        selected = {};
        api.clearState();
      },

      clearState: function () {
        isDetailOpen = false;
        $timeout(function () {
          if (isDetailOpen === false) {
            previewed = null;
            singleKeepBeingPreviewed = false;
          }
        }, 400);
      },

      isSelectedAll: function () {
        return list.length && list.length === api.getSelectedLength();
      },

      toggleSelectAll: function () {
        if (api.isSelectedAll()) {
          return api.unselectAll();
        }
        return api.selectAll();
      },

      reset: function () {
        $log.log('keepService.reset()');

        before = null;
        end = false;
        list.length = 0;
        selected = {};
        api.unselectAll();
      },

      getList: function (params) {
        if (end) {
          return $q.when([]);
        }

        var url = env.xhrBase + '/keeps/all';
        params = params || {};
        params.count = params.count || limit;
        params.before = before || void 0;

        var config = {
          params: params
        };

        return keepList.get(url, config).then(function (result) {

          var keeps = result.keeps;
          var _before = result.before;

          if (!keeps.length || keeps.length < params.count - 1) {
            end = true;
          }

          if (!_before) {
            list.length = 0;
          }

          list.push.apply(list, keeps);
          before = list.length ? list[list.length - 1].id : null;
          return keeps;
        });
      },

      joinTags: function (keeps, tags) {
        var idMap = _.reduce(tags, function (map, tag) {
          if (tag && tag.id) {
            map[tag.id] = tag;
          }
          return map;
        }, {});

        _.forEach(keeps, function (keep) {
          keep.tagList = _.map(keep.collections || keep.tags, function (tagId) {
            return idMap[tagId] || null;
          }).filter(function (tag) {
            return tag != null;
          });
        });
      },

      getChatter: function (keep) {
        if (keep && keep.url && expiredConversationCount(keep)) {
          var url = env.xhrBaseEliza + '/chatter';

          var data = {
            url: keep.url
          };

          $log.log('keepService.getChatter()', data);

          return $http.post(url, data).then(function (res) {
            var resp = res.data;
            keep.conversationCount = resp.threads;
            keep.conversationUpdatedAt = new Date();
            return resp;
          });
        }
        return $q.when({'threads': 0});
      },

      fetchScreenshotUrls: function (urls) {
        var previousCancelled = screenshotDebouncePromise && $timeout.cancel(screenshotDebouncePromise);

        if (previousCancelled) {
          // We cancelled an existing call that was in a timeout. User is likely typing actively.
          screenshotDebouncePromise = $timeout(angular.noop, 1000);
          return screenshotDebouncePromise.then(function () {
            return lookupScreenshotUrls(urls);
          });
        }

        // No previous request was going. Start a timer, but go ahead and run the screenshot lookup.
        screenshotDebouncePromise = $timeout(angular.noop, 1000);
        return lookupScreenshotUrls(urls);
      },

      prefetchImages: function (urls) {
        _.forEach(urls, function (imgUrl, key) {
          if (!(key in previewUrls) && imgUrl) {
            previewUrls[key] = imgUrl;
            doc.createElement('img').src = imgUrl;
          }
        });
      },

      keep: function (keeps, isPrivate) {
        if (!(keeps && keeps.length)) {
          return $q.when(keeps || []);
        }

        var keepPrivacy = isPrivate == null;
        isPrivate = !! isPrivate;

        var url = env.xhrBase + '/keeps/add',
          data = {
            keeps: keeps.map(function (keep) {
              return {
                title: keep.title,
                url: keep.url,
                isPrivate: keepPrivacy ? !! keep.isPrivate : isPrivate
              };
            })
          };

        $log.log('keepService.keep()', data);

        return $http.post(url, data).then(function (res) {
          console.info(res, _.clone(res.data));
          _.forEach(keeps, function (keep) {
            keep.isMyBookmark = true;
            keep.isPrivate = keepPrivacy ? !! keep.isPrivate : isPrivate;
            keep.unkept = false;
          });
          $analytics.eventTrack('user_clicked_page', {
            'action': 'keep'
          });
          return keeps;
        });
      },

      unkeep: function (keeps) {
        if (!(keeps && keeps.length)) {
          return $q.when(keeps || []);
        }

        var url, data;

        if (keeps.length === 1 && keeps[0].id) {
          url = routeService.removeSingleKeep(keeps[0].id);
          data = {};
        } else {
          url = routeService.removeKeeps;
          data = _.map(keeps, function (keep) {
            return {
              url: keep.url
            };
          });
        }

        $log.log('keepService.unkeep()', url, data);

        return $http.post(url, data).then(function () {
          _.forEach(keeps, function (keep) {
            keep.unkept = true;
            keep.isMyBookmark = false;
            if (previewed === keep) {
              api.togglePreview(keep);
            }
            if (api.isSelected(keep)) {
              api.unselect(keep);
            }
          });

          var message = keeps.length > 1 ? keeps.length + ' Keeps deleted.' : 'Keep deleted.';
          undoService.add(message, function () {
            api.keep(keeps);
          });

          $analytics.eventTrack('user_clicked_page', {
            'action': 'unkeep'
          });
          return keeps;
        });
      },

      toggleKeep: function (keeps, isPrivate) {
        var isKept = _.every(keeps, 'isMyBookmark');
        isPrivate = isPrivate == null ? _.some(keeps, 'isPrivate') : !! isPrivate;

        if (isKept) {
          return api.unkeep(keeps);
        }
        return api.keep(keeps, isPrivate);
      },

      togglePrivate: function (keeps) {
        return api.keep(keeps, !_.every(keeps, 'isPrivate'));
      },

      isEnd: function () {
        return !!end;
      },

      getSubtitle: function (mouseover) {
        var selectedCount = api.getSelectedLength(),
          numShown = list.length;

        if (mouseover) {
          if (selectedCount === numShown) {
            return 'Deselect all ' + numShown + ' Keeps below';
          }
          return 'Select all ' + numShown + ' Keeps below';
        }

        switch (selectedCount) {
        case 0:
          return null;
        case 1:
          return selectedCount + ' Keep selected';
        default:
          return selectedCount + ' Keeps selected';
        }
      },

      find: function (query, filter, context) {
        if (end) {
          return $q.when([]);
        }

        var url = routeService.search,
          data = {
            params: {
              q: query || void 0,
              f: filter || 'm',
              maxHits: 30,
              context: context || void 0
            }
          };

        $log.log('keepService.find()', data);

        return $http.get(url, data).then(function (res) {
          var data = res.data,
            hits = data.hits || [];

          if (!data.mayHaveMore) {
            end = true;
          }

          $analytics.eventTrack('user_clicked_page', {
            'action': 'searchKifi',
            'hits': hits.size,
            'mayHaveMore': data.mayHaveMore
          });

          _.forEach(hits, processHit);

          list.push.apply(list, hits);

          fetchScreenshots(hits);

          return data;
        });
      },

      getKeepsByTagId: function (tagId, params) {
        params = params || {};
        params.collection = tagId;
        return api.getList(params);
      }
    };

    return api;
  }
]);

'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService'])

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'keepService', 'tagService',
  function ($scope, profileService, keepService, tagService) {
    $scope.me = profileService.me;
    $scope.data = {draggedKeeps: null};

    $scope.$watch(function () {
      return ($scope.keeps && $scope.keeps.length || 0) + ',' + tagService.list.length;
    }, function () {
      // update antiscroll
      $scope.refreshScroll();

      if ($scope.keeps && $scope.keeps.length && tagService.list.length) {
        keepService.joinTags($scope.keeps, tagService.list);
      }
    });

    $scope.dragKeeps = function (keep, event, mouseX, mouseY) {
      var draggedKeeps = keepService.getSelected();
      if (draggedKeeps.length === 0) {
        draggedKeeps = [keep];
      }
      $scope.data.draggedKeeps = draggedKeeps;
      var draggedKeepsElement = $scope.getDraggedKeepsElement();
      var sendData = angular.toJson($scope.data.draggedKeeps);
      event.dataTransfer.setData('Text', sendData);
      event.dataTransfer.setDragImage(draggedKeepsElement[0], mouseX, mouseY);
    };

    $scope.stopDraggingKeeps = function () {
      $scope.data.draggedKeeps = null;
    };
  }
])

.directive('kfKeeps', [
  'keepService', '$document', '$log',
  function (keepService, $document, $log) {

    return {
      restrict: 'A',
      scope: {
        keeps: '=',
        keepsLoading: '=',
        keepsHasMore: '=',
        keepClick: '=',
        scrollDistance: '=',
        scrollDisabled: '=',
        scrollNext: '&'
      },
      controller: 'KeepsCtrl',
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope, element /*, attrs*/ ) {

        scope.select = keepService.select;
        scope.unselect = keepService.unselect;
        scope.toggleSelect = keepService.toggleSelect;
        scope.isSelected = keepService.isSelected;
        scope.preview = keepService.preview;
        scope.togglePreview = keepService.togglePreview;
        scope.isPreviewed = keepService.isPreviewed;

        var antiscroll = element.find('.antiscroll-inner');
        var wrapper = element.find('.keeps-wrapper');

        function bringCardIntoViewUp() {
          var elem = element.find('.detailed');
          var offset = elem.offset();
          if (!offset || !offset.top) {
            return;
          }

          if (offset.top - 300 < 0) {
            antiscroll.scrollTop(antiscroll.scrollTop() + (offset.top - 300));
          }
        }

        function bringCardIntoViewDown() {
          var elem = element.find('.detailed');
          var offset = elem.offset();
          if (!offset || !offset.top) {
            return;
          }
          var wrapperHeight = wrapper.height();

          if (offset.top + 100 > wrapperHeight) {
            antiscroll.scrollTop(antiscroll.scrollTop() + (offset.top + 100 - wrapperHeight));
          }
        }

        function keepKeyBindings(e) {
          var meta = e && (e.shiftKey || e.altKey || e.ctrlKey || e.metaKey);
          if (e && !meta && e.currentTarget && e.currentTarget.activeElement && e.currentTarget.activeElement.tagName === 'BODY') {
            var captured = false;
            /* jshint maxcomplexity: false */
            switch (e.which) {
              case 13: // enter
                var p = keepService.getHighlighted();
                keepService.togglePreview(p);
                captured = true;
                break;
              case 27: // esc
                if (keepService.isDetailOpen()) {
                  keepService.clearState();
                  captured = true;
                }
                break;
              case 38: // up
              case 75: // k
                keepService.previewPrev();
                bringCardIntoViewUp();
                captured = true;
                break;
              case 40: // down
              case 74: // j
                keepService.previewNext();
                bringCardIntoViewDown();
                captured = true;
                break;
              case 32: // space
                keepService.toggleSelect();
                captured = true;
                break;
            }
            if (captured) {
              scope.$apply();
              e.preventDefault();
            } else {
              $log.log('key', String.fromCharCode(e.which), e.which);
            }
          }
        }

        $document.on('keydown', keepKeyBindings);

        scope.$on('$destroy', function () {
          keepService.clearState();
          $document.off('keydown', keepKeyBindings);
        });

        scope.isShowMore = function () {
          return !scope.keepsLoading && scope.keepsHasMore;
        };

        scope.onClickKeep = function (keep, $event) {
          if ($event.target.tagName !== 'A') {
            if (scope.keepClick) {
              scope.keepClick(keep, $event);
            }
            if ($event.ctrlKey || $event.metaKey) {
              if (scope.isSelected(keep)) {
                scope.unselect(keep);
              } else {
                scope.select(keep);
              }
            } else {
              scope.togglePreview(keep);
            }
          }
        };

        scope.isScrollDisabled = function () {
          return scope.scrollDisabled;
        };

        if (scope.scrollDistance == null) {
          scope.scrollDistance = '100%';
        }

        scope.getDraggedKeepsElement = function () {
          var ellipsis = element.find('.kf-shadow-keep-ellipsis');
          var ellipsisCounter = element.find('.kf-shadow-keep-ellipsis-counter');
          var ellipsisCounterHidden = element.find('.kf-shadow-keep-ellipsis-counter-hidden');
          var second = element.find('.kf-shadow-keep-second');
          var last = element.find('.kf-shadow-keep-last');
          var keepHeaderHeight = 35;
          var ellipsisHeight = 28;
          if (scope.data.draggedKeeps.length === 2) {
            last.css({top: keepHeaderHeight + 'px'});
          } else if (scope.data.draggedKeeps.length === 3) {
            second.css({top: keepHeaderHeight + 'px'});
            last.css({top: 2 * keepHeaderHeight + 'px'});
          } else if (scope.data.draggedKeeps.length >= 4) {
            ellipsis.css({top: keepHeaderHeight + 'px', height: ellipsisHeight + 'px'});
            ellipsisCounter.css({left: (parseInt(ellipsis.width(), 10) - parseInt(ellipsisCounterHidden.width(), 10)) / 2});
            last.css({top: keepHeaderHeight + ellipsisHeight + 'px'});
          }
          return element.find('.kf-shadow-dragged-keeps');
        };

        var shadowDraggedKeeps = element.find('.kf-shadow-dragged-keeps');
        shadowDraggedKeeps.css({top: 0, width: element.find('.kf-my-keeps')[0].offsetWidth + 'px'});
      }
    };
  }
]);

'use strict';

angular.module('kifi.layout.leftCol', [])

.controller('LeftColCtrl', [
  '$scope', '$element', '$window', '$timeout',
  function ($scope, $element, $window, $timeout) {
    var updateHeight = _.throttle(function () {
      $element.css('height', $window.innerHeight + 'px');
    }, 100);
    angular.element($window).resize(updateHeight);

    $timeout(updateHeight);
  }
]);


'use strict';

angular.module('kifi.layout.main', [
  'kifi.undo',
  'angulartics'
])

.controller('MainCtrl', [
  '$scope', '$element', '$window', '$location', '$timeout', '$rootElement', 'undoService', 'keyIndices', 'injectedState', '$rootScope', '$analytics',
  function ($scope, $element, $window, $location, $timeout, $rootElement, undoService, keyIndices, injectedState, $rootScope, $analytics) {

    $scope.search = {};
    $scope.data = $scope.data || {};

    $scope.isEmpty = function () {
      return !$scope.search.text;
    };

    $scope.onKeydown = function (e) {
      if (e.keyCode === keyIndices.KEY_ESC) {
        $scope.clear();
      } else if (e.keyCode === keyIndices.KEY_ENTER) {
        performSearch();
      }
    };

    $scope.onFocus = function () {
      $scope.focus = true;
    };

    $scope.onBlur = function () {
      $scope.focus = false;
    };

    $scope.clear = function () {
      $scope.search.text = '';
    };

    function performSearch() {
      var text = $scope.search.text || '';
      text = _.str.trim(text);

      if (text) {
        $location.path('/find').search('q', text);
      }
      else {
        $location.path('/').search('');
      }

      // hacky solution to url event not getting fired
      $timeout(function () {
        $scope.$apply();
      });
    }

    $scope.onChange = _.debounce(performSearch, 350);

    $scope.$on('$routeChangeSuccess', function (event, current, previous) {
      if (previous && current && previous.controller === 'SearchCtrl' && current.controller !== 'SearchCtrl') {
        $scope.search.text = '';
      }
    });

    $scope.undo = undoService;

    var updateHeight = _.throttle(function () {
      $element.css('height', $window.innerHeight + 'px');
    }, 100);
    angular.element($window).resize(updateHeight);

    $timeout(updateHeight);

    var messages = {
      0: 'Welcome back!',
      2: 'Bookmark import in progress. Reload the page to update.'
    };

    function handleInjectedState(state) {
      if (state) {
        if (state.m && state.m === '1') {
          $scope.data.showEmailModal = true;
          $scope.modal = 'email';
        } else if (state.m) { // show small tooltip
          var msg = messages[state.m];
          $scope.tooltipMessage = msg;
          $timeout(function () {
            delete $scope.tooltipMessage;
          }, 5000);
        }
      }
    }
    handleInjectedState(injectedState.state);

    function initBookmarkImport(count, msgEvent) {
      $scope.modal = 'import_bookmarks';
      $scope.data.showImportModal = true;
      $scope.msgEvent = (msgEvent && msgEvent.origin && msgEvent.source && msgEvent) || false;
    }

    $rootScope.$on('showGlobalModal', function (e, modal) {
      switch (modal) {
        case 'addNetworks':
          $scope.modal = 'add_networks';
          $scope.data.showAddNetworks = true;
          break;
        case 'importBookmarks':
          initBookmarkImport.apply(null, Array.prototype.slice(arguments, 2));
          break;
        case 'addKeeps':
          $scope.modal = 'add_keeps';
          $scope.data.showAddKeeps = true;
          break;
      }
    });

    $scope.importBookmarks = function () {
      $scope.data.showImportModal = false;

      var kifiVersion = $window.document.getElementsByTagName('html')[0].getAttribute('data-kifi-ext');

      if (!kifiVersion) {
        $scope.modal = 'import_bookmarks_error';
        $scope.data.showImportError = true;
        return;
      }

      $analytics.eventTrack('user_clicked_page', {
        'action': 'bookmarkImport'
      });

      var event = $scope.msgEvent && $scope.msgEvent.origin && $scope.msgEvent.source && $scope.msgEvent;
      if (event) {
        event.source.postMessage('import_bookmarks', $scope.msgEvent.origin);
      } else {
        $window.postMessage('import_bookmarks', '*');
      }
      $scope.modal = 'import_bookmarks2';
      $scope.data.showImportModal2 = true;
    };

    if (/^Mac/.test($window.navigator.platform)) {
      $rootElement.find('body').addClass('mac');
    }
  }
]);

'use strict';

angular.module('kifi.layout.nav', ['util'])

.directive('kfNav', [
  '$location', 'util', 'keepService', 'friendService',
  function ($location, util, keepService, friendService) {
    return {
      //replace: true,
      restrict: 'A',
      templateUrl: 'layout/nav/nav.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.counts = {
          keepCount: keepService.totalKeepCount,
          friendsCount: friendService.friends.length,
          friendsNotifCount: friendService.requests.length
        };

        scope.$watch(function () {
          return friendService.requests.length;
        }, function (value) {
          scope.counts.friendsNotifCount = value;
        });

        scope.$watch(function () {
          return friendService.friends.length;
        }, function (value) {
          scope.counts.friendsCount = value;
        });

        scope.$watch(function () {
          return keepService.totalKeepCount;
        }, function (val) {
          scope.counts.keepCount = val;
        });

        scope.isActive = function (path) {
          var loc = $location.path();
          return loc === path || util.startsWith(loc, path + '/');
        };
      }
    };
  }
]);

'use strict';

angular.module('kifi.layout.rightCol', ['kifi.modal'])

.controller('RightColCtrl', [
  '$scope', '$element', '$window', 'profileService', '$q', '$http', 'env', '$timeout', 'installService', '$rootScope',
  function ($scope, $element, $window, profileService, $q, $http, env, $timeout, installService, $rootScope) {
    $scope.data = $scope.data || {};

    $scope.installInProgress = function () {
      return installService.installInProgress;
    };

    $scope.installed = function () {
      return installService.installed;
    };

    $scope.installError = function () {
      return installService.error;
    };

    $scope.triggerInstall = function () {
      installService.triggerInstall(function () {
        $scope.data.showInstallErrorModal = true;
      });
    };

    // onboarding.js is using these functions
    $window.getMe = function () {
      return (profileService.me ? $q.when(profileService.me) : profileService.fetchMe()).then(function (me) {
        me.pic200 = me.picUrl;
        return me;
      });
    };

    $window.exitOnboarding = function () {
      $scope.data.showGettingStarted = false;
      $http.post(env.xhrBase + '/user/prefs', {
        onboarding_seen: 'true'
      });
      if (!profileService.prefs.onboarding_seen) {
        $scope.importBookmarks();
      }
      $scope.$apply();
    };

    $rootScope.$on('showGettingStarted', function () {
      $scope.data.showGettingStarted = true;
    });

    $scope.importBookmarks = function () {
      var kifiVersion = $window.document.getElementsByTagName('html')[0].getAttribute('data-kifi-ext');

      if (!kifiVersion) {
        return;
      }

      $rootScope.$emit('showGlobalModal', 'importBookmarks');
    };

    $window.addEventListener('message', function (event) {
      if (event.data && event.data.bookmarkCount > 0) {
        $rootScope.$emit('showGlobalModal', 'importBookmarks', event.data.bookmarkCount, event);
      }
    });

    $scope.logout = function () {
      profileService.logout();
    };


    var updateHeight = _.throttle(function () {
      $element.css('height', $window.innerHeight + 'px');
    }, 100);
    angular.element($window).resize(updateHeight);

    $timeout(updateHeight);
  }
]);

'use strict';

angular.module('kifi.profile', [
  'util',
  'kifi.profileService',
  'kifi.profileInput',
  'kifi.routeService',
  'kifi.profileEmailAddresses',
  'kifi.profileChangePassword',
  'kifi.profileImage',
  'jun.facebook',
  'angulartics'
])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/profile', {
      templateUrl: 'profile/profile.tpl.html',
      controller: 'ProfileCtrl'
    });
  }
])

.controller('ProfileCtrl', [
  '$scope', '$http', 'profileService', 'routeService', '$window', 'socialService',
  function ($scope, $http, profileService, routeService, $window, socialService) {

    // $analytics.eventTrack('test_event', { category: 'test', label: 'controller' });

    $window.document.title = 'Kifi • Your Profile';
    socialService.refresh();

    $scope.showEmailChangeDialog = {value: false};
    $scope.showResendVerificationEmailDialog = {value: false};

    profileService.getMe().then(function (data) {
      $scope.me = data;
    });

    $scope.descInput = {};
    $scope.$watch('me.description', function (val) {
      $scope.descInput.value = val || '';
    });

    $scope.emailInput = {};
    $scope.$watch('me.primaryEmail.address', function (val) {
      $scope.emailInput.value = val || '';
    });

    $scope.addEmailInput = {};

    $scope.saveDescription = function (value) {
      profileService.postMe({
        description: value
      });
    };

    $scope.validateEmail = function (value) {
      return profileService.validateEmailFormat(value);
    };

    $scope.saveEmail = function (email) {
      if ($scope.me && $scope.me.primaryEmail.address === email) {
        return profileService.successInputActionResult();
      }

      return getEmailInfo(email).then(function (result) {
        return checkCandidateEmailSuccess(email, result.data);
      }, function (result) {
        return profileService.getEmailValidationError(result.status);
      });
    };

    $scope.addEmail = function (email) {
      return getEmailInfo(email).then(function (result) {
        return checkCandidateAddEmailSuccess(email, result.data);
      }, function (result) {
        return profileService.getEmailValidationError(result.status);
      });
    };

    $scope.isUnverified = function (email) {
      return email.value && !email.value.isPendingPrimary && email.value.isPrimary && !email.value.isVerified;
    };

    $scope.resendVerificationEmail = function (email) {
      if (!email && $scope.me && $scope.me.primaryEmail) {
        email = $scope.me.primaryEmail.address;
      }
      showVerificationAlert(email);
      profileService.resendVerificationEmail(email);
    };

    $scope.cancelPendingPrimary = function () {
      profileService.cancelPendingPrimary();
    };

    // Profile email utility functions
    var emailToBeSaved;

    $scope.cancelSaveEmail = function () {
      $scope.emailInput.value = $scope.me.primaryEmail.address;
    };

    $scope.confirmSaveEmail = function () {
      profileService.setNewPrimaryEmail(emailToBeSaved);
    };

    function showVerificationAlert(email) {
      $scope.emailForVerification = email;
      $scope.showResendVerificationEmailDialog.value = true;
    }

    function getEmailInfo(email) {
      return $http({
        url: routeService.emailInfoUrl,
        method: 'GET',
        params: {
          email: email
        }
      });
    }

    function checkCandidateEmailSuccess(email, emailInfo) {
      if (emailInfo.isPrimary || emailInfo.isPendingPrimary) {
        profileService.fetchMe();
        return;
      }
      if (emailInfo.isVerified) {
        return profileService.setNewPrimaryEmail($scope.me, emailInfo.address);
      }
      // email is available || (not primary && not pending primary && not verified)
      emailToBeSaved = email;
      $scope.showEmailChangeDialog.value = true;
      return profileService.successInputActionResult();
    }

    function checkCandidateAddEmailSuccess(email, emailInfo) {
      if (emailInfo.status === 'available') {
        profileService.addEmailAccount(email);
        showVerificationAlert(email); // todo: is the verification triggered automatically?
      }
      else {
        return profileService.failureInputActionResult(
          'This email address is already added',
          'Please use another email address.'
        );
      }
    }
  }
])

.directive('kfLinkedinConnectButton', [
  'socialService',
  function (socialService) {
    return {
      restrict: 'A',
      link: function (scope) {
        scope.isLinkedInConnected = socialService.linkedin && !!socialService.linkedin.profileUrl;

        scope.linkedin = socialService.linkedin;

        scope.$watch(function () {
          return socialService.linkedin && socialService.linkedin.profileUrl;
        }, function () {
          var linkedin = socialService.linkedin;
          if (linkedin && linkedin.profileUrl) {
            scope.isLinkedInConnected = true;
            scope.liProfileUrl = linkedin.profileUrl;
          } else {
            scope.isLinkedInConnected = false;
            scope.liProfileUrl = '';
          }
        });

        scope.connectLinkedIn = socialService.connectLinkedIn;
        scope.disconnectLinkedIn = socialService.disconnectLinkedIn;
      }
    };
  }
])

.directive('kfFacebookConnectButton', [
  'socialService',
  function (socialService) {
    return {
      restrict: 'A',
      link: function (scope) {
        scope.isFacebookConnected = socialService.facebook && !!socialService.facebook.profileUrl;

        scope.facebook = socialService.facebook;

        scope.$watch(function () {
          return socialService.facebook && socialService.facebook.profileUrl;
        }, function () {
          var facebook = socialService.facebook;
          if (facebook && facebook.profileUrl) {
            scope.isFacebookConnected = true;
            scope.fbProfileUrl = facebook.profileUrl;
          } else {
            scope.isFacebookConnected = false;
            scope.fbProfileUrl = '';
          }
        });

        scope.connectFacebook = socialService.connectFacebook;
        scope.disconnectFacebook = socialService.disconnectFacebook;
      }
    };
  }
])

.directive('kfEmailImport', [
  'profileService', '$window', 'env', 'socialService',
  function (profileService, $window, env, socialService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'profile/emailImport.tpl.html',
      link: function (scope) {

        scope.addressBookImportText = 'Import a Gmail account';

        socialService.refresh().then(function () {
          scope.addressBooks = socialService.addressBooks;
          if (socialService.addressBooks && socialService.addressBooks.length > 0) {
            scope.addressBookImportText = 'Import another Gmail account';
          }
        });

        scope.importGmailContacts = function () {
          $window.location = env.origin + '/importContacts';
        };
      }
    };
  }
]);

'use strict';

angular.module('kifi.profileChangePassword', ['util', 'kifi.profileService'])

.directive('kfProfileChangePassword', [
  'profileService', 'keyIndices',
  function (profileService, keyIndices) {
    return {
      restrict: 'A',
      scope: {},
      templateUrl: 'profile/profileChangePassword.tpl.html',
      link: function (scope, element) {
        scope.isOpen = false;
        scope.inputs = {oldPassword: '', newPassword1: '', newPassword2: ''};

        scope.toggle = function () {
          scope.isOpen = !scope.isOpen;
          if (scope.isOpen) {
            scope.successMessage = '';
          }
        };

        element.find('input').on('keydown', function (e) {
          switch (e.which) {
            case keyIndices.KEY_ESC:
              this.blur();
              break;
            case keyIndices.KEY_ENTER:
              scope.$apply(scope.updatePassword);
              break;
          }
        });

        scope.updatePassword = function () {
          scope.successMessage = '';
          if (scope.inputs.oldPassword.length < 7) {
            scope.errorMessage = 'Your current password is not correct.';
          } else if (scope.inputs.newPassword1 !== scope.inputs.newPassword2) {
            scope.errorMessage = 'Your new passwords do not match.';
          } else if (scope.inputs.newPassword1.length < 7) {
            scope.errorMessage = 'Your password needs to be longer than 7 characters.';
          } else if (scope.inputs.oldPassword === scope.inputs.newPassword1) {
            scope.errorMessage = 'Your new password needs to be different from your current one.';
          } else {
            scope.errorMessage = '';
            profileService.sendChangePassword(scope.inputs.oldPassword, scope.inputs.newPassword1)
              .then(function () {
                scope.successMessage = 'Password updated!';
                scope.inputs = {};
                scope.toggle();
              }, function (result) {
                if (result.data.error === 'bad_old_password') {
                  scope.errorMessage = 'Your current password is not correct.';
                } else if (result.data.error === 'bad_new_password') {
                  scope.errorMessage = 'Your password needs to be longer than 7 characters.';
                } else {
                  scope.errorMessage = 'An error occured. Try again?';
                }
              });
          }
        };
      }
    };
  }
]);

'use strict';

angular.module('kifi.profileEmailAddresses', ['util', 'kifi.profileService'])

.directive('kfProfileEmailAddresses', [
  '$document', 'keyIndices', 'profileService',
  function ($document, keyIndices, profileService) {
    return {
      restrict: 'A',
      scope: {
        state: '=inputState',
        emailList: '=',
        validateEmailAction: '&',
        addEmailAction: '&',
        resendVerificationEmailAction: '&'
      },
      templateUrl: 'profile/profileEmailAddresses.tpl.html',
      link: function (scope, element) {
        scope.isOpen = false;
        scope.emailWithActiveDropdown = null;
        scope.showEmailDeleteDialog = {};
        scope.emailToBeDeleted = null;

        scope.toggle = function () {
          scope.isOpen = !scope.isOpen;
        };

        scope.enableAddEmail = function () {
          scope.state.editing = true;
        };

        scope.validateEmail = function (value) {
          return scope.validateEmailAction({value: value});
        };

        scope.addEmail = function (value) {
          return scope.addEmailAction({value: value});
        };

        scope.resendVerificationEmail = function (value) {
          return scope.resendVerificationEmailAction({value: value});
        };

        scope.openDropdownForEmail = function (event, email) {
          if (scope.emailWithActiveDropdown !== email) {
            scope.emailWithActiveDropdown = email;
            event.stopPropagation();
            $document.bind('click', closeDropdown);
          }
        };

        scope.deleteEmail = function (email) {
          scope.emailToBeDeleted = email;
          scope.showEmailDeleteDialog.value = true;
        };

        scope.confirmDeleteEmail = function () {
          profileService.deleteEmailAccount(scope.emailToBeDeleted);
        };
        
        scope.makePrimary = function (email) {
          profileService.makePrimary(email);
        };

        element.find('input')
          .on('keydown', function (e) {
            switch (e.which) {
              case keyIndices.KEY_ESC:
                scope.$apply(scope.cancelAddEmail);
                break;
              case keyIndices.KEY_ENTER:
                scope.$apply(scope.addEmail);
                break;
            }
          });

        function closeDropdown() {
          scope.$apply(function () { scope.emailWithActiveDropdown = null; });
          $document.unbind('click', closeDropdown);
        }
      }
    };
  }
]);

'use strict';

angular.module('kifi.profileImage', [
  'angulartics'
])

.directive('kfProfileImage', [
  '$document', '$timeout', '$compile', '$templateCache', '$window', '$q', '$http', 'env', 'profileService', '$analytics',
  function ($document, $timeout, $compile, $templateCache, $window, $q, $http, env, profileService, $analytics) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        picUrl: '='
      },
      templateUrl: 'profile/profileImage.tpl.html',
      link: function (scope, element) {
        scope.showImageEditDialog = {value: false};
        scope.showImageUploadingModal = {value: false};
        scope.showImageUploadFailedDialog = {value: false};

        var maskOffset = 40, maskSize;
        var positioning = {};
        var dragging = {};
        var isImageLoaded = false;
        var PHOTO_BINARY_UPLOAD_URL = env.xhrBase + '/user/pic/upload',
          PHOTO_CROP_UPLOAD_URL = env.xhrBase + '/user/pic';
        var photoXhr2;

        function refreshZoom() {
          if (!isImageLoaded) {
            return;
          }
          var scale = Math.pow(scope.zoomSlider.value / scope.zoomSlider.max, 2);
          positioning.currentWidth = positioning.minimumWidth + scale * (positioning.maximumWidth - positioning.minimumWidth);
          positioning.currentHeight = positioning.minimumHeight + scale * (positioning.maximumHeight - positioning.minimumHeight);
          imageElement.css({backgroundSize: positioning.currentWidth + 'px ' + positioning.currentHeight + 'px'});
          updateOffset();
          updateImagePosition();
        }

        scope.zoomSlider = {
          orientation: 'horizontal',
          min: 0,
          max: 100,
          range: 'min',
          change: refreshZoom,
          slide: refreshZoom
        };

        function cappedLength(length, low, high) {
          if (length > low) {
            return low;
          }
          if (length < high) {
            return high;
          }
          return length;
        }

        function updateImagePosition() {
          imageElement.css({backgroundPosition: positioning.currentLeft + 'px ' + positioning.currentTop + 'px'});
        }

        function setPosition(left, top) {
          positioning.currentLeft = cappedLength(left, maskOffset, imageElement.width() - maskOffset - positioning.currentWidth);
          positioning.currentTop = cappedLength(top, maskOffset, imageElement.height() - maskOffset - positioning.currentHeight);
        }

        function updateOffset() {
          var left = imageElement.width() / 2 - positioning.currentWidth * positioning.centerXRatio;
          var top = imageElement.height() / 2 - positioning.currentHeight * positioning.centerYRatio;
          setPosition(left, top);
        }

        function updateRatio() {
          positioning.centerXRatio = (imageElement.width() / 2 - positioning.currentLeft) / positioning.currentWidth;
          positioning.centerYRatio = (imageElement.height() / 2 - positioning.currentTop) / positioning.currentHeight;
        }

        var fileInput = element.find('.profile-image-file');
        var imageElement, imageMask;
        $timeout(function () {
          imageElement = element.find('.kf-profile-image-dialog-image');
          imageMask = element.find('.kf-profile-image-dialog-mask');
          setupImageDragging();
        });

        function startImageDragging(e) {
          dragging.initialMouseX = e.pageX;
          dragging.initialMouseY = e.pageY;
          dragging.initialLeft = positioning.currentLeft;
          dragging.initialTop = positioning.currentTop;
          $document.on('mousemove', updateImageDragging);
          $document.on('mouseup', stopImageDragging);
        }

        function updateImageDragging(e) {
          var left = dragging.initialLeft + e.pageX - dragging.initialMouseX;
          var top = dragging.initialTop + e.pageY - dragging.initialMouseY;
          setPosition(left, top);
          updateImagePosition();
        }

        function stopImageDragging() {
          $document.off('mousemove', updateImageDragging);
          $document.off('mousemove', stopImageDragging);
          updateRatio();
        }

        function setupImageDragging() {
          imageMask.on('mousedown', startImageDragging);
        }

        function uploadPhotoXhr2(files) {
          var file = Array.prototype.filter.call(files, isImage)[0];
          if (file) {
            if (photoXhr2) {
              photoXhr2.abort();
            }

            var xhr = new $window.XMLHttpRequest();
            photoXhr2 = xhr;

            var deferred = $q.defer();

            xhr.withCredentials = true;
            xhr.upload.addEventListener('progress', function (e) {
              if (e.lengthComputable) {
                deferred.notify(e.loaded / e.total);
              }
            });

            xhr.addEventListener('load', function () {
              deferred.resolve(JSON.parse(xhr.responseText));
            });

            xhr.addEventListener('loadend', function () {
              if (photoXhr2 === xhr) {
                photoXhr2 = null;
              }
              //todo(martin) We cannot directly check the state of the promise
              /*if (deferred.state() === 'pending') {
               deferred.reject();
               }*/
            });

            xhr.open('POST', PHOTO_BINARY_UPLOAD_URL, true);
            xhr.send(file);

            return {
              file: file,
              promise: deferred.promise
            };
          }

          //todo(martin): Notify user
        }

        function isImage(file) {
          return file.type.search(/^image\/(?:bmp|jpg|jpeg|png|gif)$/) === 0;
        }

        scope.selectFile = function () {
          fileInput.click();
        };

        scope.fileChosen = function (files) {
          // this function is called via onchange attribute in input field - we need to let angular know about it
          scope.$apply(function () {
            isImageLoaded = false;
            scope.files = files;
            if (scope.files.length === 0) {
              return;
            }
            // Using a local file reader so that the user can edit the image without uploading it to the server first
            var reader = new FileReader();
            reader.onload = function (e) {
              showImageEditingTool(e.target.result);
            };
            reader.readAsDataURL(scope.files[0]);
          });
        };

        function showImageEditingTool(imageUrl) {
          scope.$apply(function () {
            imageElement.css({
              background: 'url(' + imageUrl + ') no-repeat'
            });
            maskSize = imageElement.width() - 2 * maskOffset;
            var image = new Image();
            image.onload = function () {
              var img = this;
              scope.$apply(function () {
                positioning.imageWidth = img.width || 1;
                positioning.imageHeight = img.height || 1;
                var imageRatio = positioning.imageWidth / positioning.imageHeight;
                var maxZoomFactor = 1.5;
                if (imageRatio < 1) {
                  positioning.minimumWidth = maskSize;
                  positioning.minimumHeight = positioning.minimumWidth / imageRatio;
                  positioning.maximumWidth = maxZoomFactor * Math.max(positioning.imageWidth, maskSize);
                  positioning.maximumHeight = positioning.maximumWidth / imageRatio;
                } else {
                  positioning.minimumHeight = maskSize;
                  positioning.minimumWidth = positioning.minimumHeight * imageRatio;
                  positioning.maximumHeight = maxZoomFactor * Math.max(positioning.imageHeight, maskSize);
                  positioning.maximumWidth = positioning.maximumHeight * imageRatio;
                }
                scope.zoomSlider.value = 50;
                positioning.centerXRatio = 0.5;
                positioning.centerYRatio = 0.5;
                scope.showImageEditDialog.value = true;
                isImageLoaded = true;
                refreshZoom();
              });
            };
            image.src = imageUrl;
          });
        }

        scope.resetChooseImage = function () {
          fileInput.val(null);
        };

        function imageUploadError() {
          scope.showImageUploadingModal.value = false;
          scope.showImageUploadFailedDialog.value = true;
          scope.resetChooseImage();
        }

        scope.uploadImage = function () {
          scope.showImageUploadingModal.value = true;
          var upload = uploadPhotoXhr2(scope.files);
          if (upload) {
            upload.promise.then(function (result) {
              var scaling = positioning.imageWidth / positioning.currentWidth;
              var data = {
                picToken: result && result.token,
                picWidth: positioning.imageWidth,
                picHeight: positioning.imageHeight,
                cropX: Math.floor(scaling * (maskOffset - positioning.currentLeft)),
                cropY: Math.floor(scaling * (maskOffset - positioning.currentTop)),
                cropSize: Math.floor(Math.min(scaling * maskSize, scaling * maskSize))
              };
              $http.post(PHOTO_CROP_UPLOAD_URL, data)
              .then(function () {
                profileService.fetchMe();
                scope.showImageUploadingModal.value = false;
                scope.resetChooseImage();
                $analytics.eventTrack('user_clicked_page', {
                  'action': 'uploadImage'
                });
              }, imageUploadError);
            }, imageUploadError);
          } else {
            imageUploadError();
          }
        };
      }
    };
  }
]);

'use strict';

angular.module('kifi.profileInput', ['util', 'kifi.profileService'])

.directive('kfProfileInput', [
  '$timeout', '$q', 'keyIndices', 'util',
  function ($timeout, $q, keyIndices, util) {
    return {
      restrict: 'A',
      scope: {
        state: '=inputState',
        validateAction: '&inputValidateAction',
        saveAction: '&inputSaveAction',
        explicitEnabling: '=',
        actionLabel: '@'
      },
      transclude: true,
      templateUrl: 'profile/profileInput.tpl.html',
      link: function (scope, element) {
        scope.state.editing = scope.state.invalid = false;

        var cancelEditPromise;

        element.find('input')
          .on('keydown', function (e) {
            switch (e.which) {
            case keyIndices.KEY_ESC:
              scope.$apply(scope.cancel);
              break;
            case keyIndices.KEY_ENTER:
              scope.$apply(scope.save);
              break;
            }
          })
          .on('blur', function () {
            // give enough time for save() to fire. todo(martin): find a more reliable solution
            cancelEditPromise = $timeout(scope.cancel, 100);
          })
          .on('focus', function () {
            $timeout(function () { setEditState(); });
          });

        function cancelCancelEdit() {
          if (cancelEditPromise) {
            $timeout.cancel(cancelEditPromise);
            cancelEditPromise = null;
          }
        }

        function updateValue(value) {
          scope.state.value = scope.state.currentValue = value;
        }

        function setInvalid(error) {
          scope.state.invalid = true;
          scope.errorHeader = error.header || '';
          scope.errorBody = error.body || '';
        }

        function setEditState() {
          cancelCancelEdit();
          scope.state.editing = true;
          scope.state.invalid = false;
        }

        scope.edit = function () {
          scope.state.currentValue = scope.state.value;
          setEditState();
        };

        scope.cancel = function () {
          scope.state.value = scope.state.currentValue;
          scope.state.editing = false;
        };

        scope.save = function () {
          // Validate input
          var value = util.trimInput(scope.state.value);
          var validationResult = scope.validateAction({value: value});
          if (validationResult && !validationResult.isSuccess && validationResult.error) {
            setInvalid(validationResult.error);
            return;
          }
          scope.state.prevValue = scope.state.currentValue;
          updateValue(value);
          scope.state.editing = false;

          // Save input
          $q.when(scope.saveAction({value: value})).then(function (result) {
            if (result && !result.isSuccess) {
              if (result.error) {
                setInvalid(result.error);
              }
              updateValue(scope.state.prevValue);
            }
          });
        };
      }
    };
  }
]);

'use strict';

angular.module('kifi.profileCard', ['kifi.profileService'])

.directive('kfProfileCard', [
  'profileService', '$analytics',
  function (profileService, $analytics) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'profileCard/profileCard.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.me = profileService.me;

        profileService.fetchMe();

        scope.data = scope.data || {};
        scope.openHelpRankHelp = function () {
          scope.data.showHelpRankHelp = true;
          $analytics.eventTrack('user_viewed_page', {
            'type': 'HelpRankHelp'
          });
        };

        scope.yesLikeHelpRank = function () {
          scope.data.showHelpRankHelp = false;
          $analytics.eventTrack('user_clicked_page', {
            'action': 'yesLikeHelpRank'
          });
        };

        scope.noLikeHelpRank = function () {
          scope.data.showHelpRankHelp = false;
          $analytics.eventTrack('user_clicked_page', {
            'action': 'noLikeHelpRank'
          });
        };


      }
    };
  }
]);

'use strict';

angular.module('kifi.profileService', [
  'kifi.routeService',
  'angulartics'
])

.factory('profileService', [
  '$http', 'env', '$q', 'util', 'routeService', 'socialService', '$analytics', '$window', '$rootScope',
  function ($http, env, $q, util, routeService, socialService, $analytics, $window, $rootScope) {

    var me = {
      seqNum: 0
    };
    var prefs = {};

    $rootScope.$on('social.updated', function () {
      fetchMe();
    });

    function updateMe(data) {
      angular.forEach(data, function (val, key) {
        me[key] = val;
      });
      me.picUrl = routeService.formatPicUrl(me.id, me.pictureName);
      me.primaryEmail = getPrimaryEmail(me.emails);
      me.seqNum++;
      socialService.setExpiredTokens(me.notAuthed);
      return me;
    }

    function fetchMe() {
      return $http.get(routeService.profileUrl).then(function (res) {
        return updateMe(res.data);
      });
    }

    function getMe() {
      return me.seqNum > 0 ? $q.when(me) : fetchMe();
    }

    function postMe(data) {
      return $http.post(routeService.profileUrl, data).then(function (res) {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'updateProfile'
        });
        return updateMe(res.data);
      });
    }

    function getPrimaryEmail(emails) {
      var actualPrimary = _.find(emails, 'isPrimary');
      if (actualPrimary) {
        return actualPrimary;
      } else {
        var placeholderPrimary = _.find(emails, 'isVerified') || emails[0] || null;
        if (placeholderPrimary) {
          _.map(emails, function (email) {
            if (email === placeholderPrimary) {
              email.isPlaceholderPrimary = true;
            }
          })
        }
        return placeholderPrimary;
      }
    }

    function removeEmailInfo(emails, addr) {
      emails = emails || me.emails;
      for (var i = emails.length - 1; i >= 0; i--) {
        if (emails[i].address === addr) {
          emails.splice(i, 1);
        }
      }
    }

    function unsetPrimary(emails) {
      var primary = getPrimaryEmail(emails);
      if (primary) {
        primary.isPrimary = false;
      }
    }

    function cloneEmails(me) {
      return { emails:  _.clone(me.emails, true) };
    }

    function setNewPrimaryEmail(email) {
      getMe().then(function (me) {
        var props = cloneEmails(me);
        removeEmailInfo(props.emails, email);
        unsetPrimary(props.emails);
        props.emails.unshift({
          address: email,
          isPrimary: true
        });
        return postMe(props);
      });
    }

    function makePrimary(email) {
      var props = cloneEmails(me);
      unsetPrimary(props.emails);
      _.find(props.emails, function (info) {
        if (info.address === email) {
          info.isPrimary = true;
        }
      });
      return postMe(props);
    }

    function resendVerificationEmail(email) {
      return $http({
        url: routeService.resendVerificationUrl,
        method: 'POST',
        params: {email: email}
      });
    }

    function cancelPendingPrimary() {
      getMe().then(function (me) {
        if (me.primaryEmail && me.primaryEmail.isPendingPrimary) {
          return deleteEmailAccount(me.primaryEmail.address);
        }
      });
    }

    function addEmailAccount(email) {
      var props = cloneEmails(me);
      props.emails.push({
        address: email,
        isPrimary: false
      });
      return postMe(props);
    }

    function deleteEmailAccount(email) {
      var props = cloneEmails(me);
      removeEmailInfo(props.emails, email);
      return postMe(props);
    }

    function validateEmailFormat(email) {
      if (!email) {
        return failureInputActionResult('This field is required');
      } else if (!util.validateEmail(email)) {
        return invalidEmailValidationResult();
      }
      return successInputActionResult();
    }

    function failureInputActionResult(errorHeader, errorBody) {
      return {
        isSuccess: false,
        error: {
          header: errorHeader,
          body: errorBody
        }
      };
    }

    function successInputActionResult() {
      return {isSuccess: true};
    }

    function getEmailValidationError(status) {
      switch (status) {
      case 400: // bad format
        return invalidEmailValidationResult();
      case 403: // belongs to another user
        return failureInputActionResult(
          'This email address is already taken',
          'This email address belongs to another user.<br>Please enter another email address.'
        );
      }
    }

    function invalidEmailValidationResult() {
      return failureInputActionResult('Invalid email address', 'Please enter a valid email address');
    }

    function sendChangePassword(oldPassword, newPassword) {
      return $http.post(routeService.userPasswordUrl, {
        oldPassword: oldPassword,
        newPassword: newPassword
      }).then(function () {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'changePassword'
        });
      });
    }

    function fetchPrefs() {
      return $http.get(routeService.prefs).then(function (p) {
        util.replaceObjectInPlace(prefs, p.data);
        return p.data;
      });
    }

    function logout() {
      $window.location = routeService.logout;
    }

    return {
      me: me, // when mutated, you MUST increment me.seqNum
      fetchMe: fetchMe,
      getMe: getMe,
      postMe: postMe,
      logout: logout,
      fetchPrefs: fetchPrefs,
      prefs: prefs,
      setNewPrimaryEmail: setNewPrimaryEmail,
      makePrimary: makePrimary,
      resendVerificationEmail: resendVerificationEmail,
      cancelPendingPrimary: cancelPendingPrimary,
      addEmailAccount: addEmailAccount,
      deleteEmailAccount: deleteEmailAccount,
      validateEmailFormat: validateEmailFormat,
      failureInputActionResult: failureInputActionResult,
      successInputActionResult: successInputActionResult,
      getEmailValidationError: getEmailValidationError,
      sendChangePassword: sendChangePassword
    };
  }
]);

'use strict';

angular.module('kifi.search', [
  'util',
  'kifi.keepService'
])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/find', {
      templateUrl: 'search/search.tpl.html',
      controller: 'SearchCtrl'
    });
  }
])

.controller('SearchCtrl', [
  '$scope', 'keepService', '$routeParams', '$location', '$window',
  function ($scope, keepService, $routeParams, $location, $window) {
    keepService.reset();

    if ($scope.search) {
      $scope.search.text = $routeParams.q;
    }

    if (!$routeParams.q) {
      // No or blank query
      $location.path('/');
    }

    var query = $routeParams.q || '',
      filter = $routeParams.f || 'm',
      lastResult = null;

    $window.document.title = query === '' ? 'Kifi • Search' : 'Kifi • ' + query;

    $scope.keepService = keepService;
    $scope.keeps = keepService.list;

    $scope.results = {
      myTotal: 0,
      friendsTotal: 0,
      othersTotal: 0
    };

    $scope.isFilterSelected = function (type) {
      return filter === type;
    };

    function getFilterCount(type) {
      switch (type) {
      case 'm':
        return $scope.results.myTotal;
      case 'f':
        return $scope.results.friendsTotal;
      case 'a':
        return $scope.results.othersTotal;
      }
    }

    $scope.isEnabled = function (type) {
      if ($scope.isFilterSelected(type)) {
        return false;
      }
      return !!getFilterCount(type);
    };

    $scope.getFilterUrl = function (type) {
      if ($scope.isEnabled(type)) {
        var count = getFilterCount(type);
        if (count) {
          return '/find?q=' + query + '&f=' + type;
        }
      }
      return '';
    };

    $scope.toggleSelectAll = keepService.toggleSelectAll;
    $scope.isSelectedAll = keepService.isSelectedAll;

    $scope.isMultiChecked = function () {
      return keepService.getSelectedLength() > 0 && !keepService.isSelectedAll();
    };

    $scope.isCheckEnabled = function () {
      return $scope.keeps.length;
    };

    $scope.hasMore = function () {
      return !keepService.isEnd();
    };

    $scope.mouseoverCheckAll = false;

    $scope.onMouseoverCheckAll = function () {
      $scope.mouseoverCheckAll = true;
    };

    $scope.onMouseoutCheckAll = function () {
      $scope.mouseoverCheckAll = false;
    };

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Searching…';
      }

      var subtitle = keepService.getSubtitle($scope.mouseoverCheckAll);
      if (subtitle) {
        return subtitle;
      }

      var numShown = $scope.keeps.length;
      switch (numShown) {
      case 0:
        return 'Sorry, no results found for “' + query + '”';
      case 1:
        return '1 result found';
      default:
        return 'Top ' + numShown + ' results';
      }
    };

    $scope.scrollDistance = '100%';
    $scope.scrollDisabled = false;

    $scope.analyticsTrack = function (keep, $event) {
      return [keep, $event]; // log analytics for search click here
    };

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;
      keepService.find(query, filter, lastResult && lastResult.context).then(function (data) {
        $scope.loading = false;

        $scope.results.myTotal = $scope.results.myTotal || data.myTotal;
        $scope.results.friendsTotal = $scope.results.friendsTotal || data.friendsTotal;
        $scope.results.othersTotal = $scope.results.othersTotal || data.othersTotal;

        if (keepService.isEnd()) {
          $scope.scrollDisabled = true;
        }

        lastResult = data;
      });
    };

    $scope.getNextKeeps();
  }
]);

'use strict';

angular.module('kifi.social.networksNeedAttention', [])


.directive('kfNetworksNeedAttention', ['socialService', '$rootScope',
  function (socialService, $rootScope) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'social/networksNeedAttention.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.networksNeedAttention = function () {
          return Object.keys(socialService.expiredTokens).length > 0;
        };
        scope.data = {};
        scope.doShow = function () {
          $rootScope.$emit('showGlobalModal', 'addNetworks');
        };
      }
    };
  }
]);

'use strict';

angular.module('kifi.social', ['kifi.socialService'])

.directive('kfSocialConnectNetworks', [
  'socialService',
  function (socialService) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'social/connectNetworks.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.data = scope.data || {};
        scope.data.show = true;

        scope.facebook = socialService.facebook;
        scope.linkedin = socialService.linkedin;
        scope.gmail = socialService.gmail;
        scope.expiredTokens = socialService.expiredTokens;
        scope.connectFacebook = socialService.connectFacebook;
        scope.connectLinkedIn = socialService.connectLinkedIn;
        scope.importGmail = socialService.importGmail;

        scope.isRefreshingSocialGraph = socialService.isRefreshingSocialGraph;
        scope.refreshingGraphs = socialService.refreshingGraphs;


        scope.facebookStatus = function () {
          if (scope.refreshingGraphs.network.facebook) {
            return 'refreshing';
          } else if (scope.expiredTokens.facebook) {
            return 'expired';
          }
          return 'good';
        };

        scope.linkedinStatus = function () {
          if (scope.refreshingGraphs.network.linkedin) {
            return 'refreshing';
          } else if (scope.expiredTokens.linkedin) {
            return 'expired';
          }
          return 'good';
        };

        socialService.refresh();

      }
    };
  }
]);


'use strict';

angular.module('kifi.socialService', [
  'angulartics'
])

.factory('socialService', [
  'routeService', '$http', 'util', '$rootScope', 'Clutch', '$window', '$q', '$analytics', '$timeout',
  function (routeService, $http, util, $rootScope, Clutch, $window, $q, $analytics, $timeout) {

    var networks = [],
        facebook = {},
        linkedin = {},
        gmail = [],
        addressBooks = [],
        expiredTokens = {},
        isRefreshingSocialGraph = false,
        importStarted = false,
        refreshingGraphs = { network: {}, abook: {} },
        updateLock = false;

    var clutchConfig = {
      cacheDuration: 5000
    };

    var checkIfUpdatingGraphs = function (times) {
      if (updateLock) {
        return;
      }

      updateLock = true;
      times = times === undefined ? 10 : times;
      if (times < 0) {
        // We've looped enough times. Clean up, and stop trying.
        importStarted = false;
        util.replaceObjectInPlace(refreshingGraphs, { network: {}, abook: {} });
        updateLock = false;
        return;
      }

      $http.get(routeService.importStatus).then(function (res) {
        util.replaceObjectInPlace(refreshingGraphs, res.data);

        if (_.size(res.data.network) > 0 || _.size(res.data.abook) > 0) {
          isRefreshingSocialGraph = true;
          if (!importStarted) {
            // Did we just start an import?
            importStarted = true;
            times = 20; // reset times, so we'll check more.
          }
          $timeout(function () {
            updateLock = false;
            checkIfUpdatingGraphs(--times);
          }, 2000);
          return;
        } else {
          // Empty object returned just now.
          if (importStarted) {
            // We previously knew about an import, and since it's empty, we're done.
            isRefreshingSocialGraph = importStarted = false;
            updateLock = false;
            $rootScope.$emit('social.updated');
            return;
          } else {
            // No import ongoing, and we've never seen evidence of an import. Check again.
            $timeout(function () {
              updateLock = false;
              checkIfUpdatingGraphs(--times);
            }, 3000);
          }
        }

      });
    };

    var networksBackend = new Clutch(function () {
      return $http.get(routeService.networks).then(function (res) {
        _.remove(res.data, function (value) {
          return value.network === 'fortytwo';
        });
        util.replaceArrayInPlace(networks, res.data);
        util.replaceObjectInPlace(facebook, _.find(networks, function (n) {
          return n.network === 'facebook';
        }));
        util.replaceObjectInPlace(linkedin, _.find(networks, function (n) {
          return n.network === 'linkedin';
        }));

        return res.data;
      });
    }, clutchConfig);

    var addressBooksBackend = new Clutch(function () {
      return $http.get(routeService.abooksUrl).then(function (res) {
        util.replaceArrayInPlace(addressBooks, res.data);
        util.replaceArrayInPlace(gmail, _.filter(addressBooks, function (elem) {
          return elem.origin === 'gmail';
        }));
        return res.data;
      });
    }, clutchConfig);

    var api = {
      networks: networks,
      addressBooks: addressBooks,
      refresh: function () {
        checkIfUpdatingGraphs(1);
        return $q.all([addressBooksBackend.get(), networksBackend.get()]);
      },
      facebook: facebook,
      linkedin: linkedin,
      gmail: gmail,

      refreshSocialGraph: function () {
        isRefreshingSocialGraph = true;
        checkIfUpdatingGraphs(); // init refreshing polling
        return $http.post(routeService.refreshNetworks);
      },

      checkIfUpdatingGraphs: checkIfUpdatingGraphs,

      checkIfRefreshingSocialGraph: function () {
        return isRefreshingSocialGraph;
      },

      refreshingGraphs: refreshingGraphs,

      connectFacebook: function () {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'connectFacebook'
        });
        $window.location.href = routeService.linkNetwork('facebook');
      },

      connectLinkedIn: function () {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'connectLinkedIn'
        });
        $window.location.href = routeService.linkNetwork('linkedin');
      },

      importGmail: function () {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'importGmail'
        });
        $window.location.href = routeService.importGmail;
      },

      disconnectFacebook: function () {
        return $http.post(routeService.disconnectNetwork('facebook')).then(function (res) {
          util.replaceObjectInPlace(facebook, {});
          $analytics.eventTrack('user_clicked_page', {
            'action': 'disconnectFacebook'
          });
          return res;
        });
      },

      disconnectLinkedIn: function () {
        return $http.post(routeService.disconnectNetwork('linkedin')).then(function (res) {
          util.replaceObjectInPlace(linkedin, {});
          $analytics.eventTrack('user_clicked_page', {
            'action': 'disconnectLinkedin'
          });
          return res;
        });
      },

      setExpiredTokens: function (networks) {
        var obj = {};
        networks.forEach(function (network) {
          obj[network] = true;
        });
        util.replaceObjectInPlace(expiredTokens, obj);
      },

      expiredTokens: expiredTokens
    };

    return api;
  }
]);

'use strict';

angular.module('kifi.tagKeeps', ['util', 'kifi.keepService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/tag/:tagId', {
      templateUrl: 'tagKeeps/tagKeeps.tpl.html',
      controller: 'TagKeepsCtrl'
    });
  }
])

.controller('TagKeepsCtrl', [
  '$scope', 'keepService', 'tagService', '$routeParams', '$window',
  function ($scope, keepService, tagService, $routeParams, $window) {

    keepService.reset();
    $scope.keepService = keepService;
    $scope.keeps = keepService.list;

    var tagId = $routeParams.tagId || '';

    $scope.toggleSelectAll = keepService.toggleSelectAll;
    $scope.isSelectedAll = keepService.isSelectedAll;

    $scope.isMultiChecked = function () {
      return keepService.getSelectedLength() > 0 && !keepService.isSelectedAll();
    };

    $scope.isCheckEnabled = function () {
      return $scope.keeps.length;
    };

    $scope.hasMore = function () {
      return !keepService.isEnd();
    };

    $scope.mouseoverCheckAll = false;

    $scope.onMouseoverCheckAll = function () {
      $scope.mouseoverCheckAll = true;
    };

    $scope.onMouseoutCheckAll = function () {
      $scope.mouseoverCheckAll = false;
    };

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      }

      var subtitle = keepService.getSubtitle($scope.mouseoverCheckAll);
      if (subtitle) {
        return subtitle;
      }

      var numShown = $scope.keeps.length;
      switch (numShown) {
      case 0:
        return 'No Keeps in this tag';
      case 1:
        return 'Showing the only Keep in this tag';
      case 2:
        return 'Showing both Keeps in this tag';
      }
      if (keepService.isEnd()) {
        return 'Showing all ' + numShown + ' Keeps in this tag';
      }
      return 'Showing the ' + numShown + ' latest Keeps in this tag';
    };

    $scope.scrollDistance = '100%';
    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;
      return keepService.getKeepsByTagId(tagId).then(function (list) {
        $scope.loading = false;

        if (keepService.isEnd()) {
          $scope.scrollDisabled = true;
        }

        return list;
      });
    };

    $scope.getNextKeeps();

    tagService.promiseById(tagId).then(function (tag) {
      $window.document.title = 'Kifi • ' + tag.name;
      $scope.tag = tag || null;
    });

  }
]);

'use strict';

angular.module('kifi.tagItem', ['kifi.tagService'])

.directive('kfTagItem', [
  '$timeout', '$document', 'tagService', 'keyIndices', 'util',
  function ($timeout, $document, tagService, keyIndices, util) {
    return {
      restrict: 'A',
      scope: {
        tag: '=',
        takeFocus: '&',
        releaseFocus: '&',
        watchTagReorder: '&',
        reorderTag: '&',
        hasNewLocation: '&',
        viewTag: '&',
        removeTag: '&'
      },
      replace: true,
      templateUrl: 'tags/tagItem.tpl.html',
      link: function (scope, element) {
        scope.isRenaming = false;
        scope.isWaiting = false;
        scope.isDropdownOpen = false;
        scope.renameTag = {};
        scope.isHovering = false;
        var input = element.find('input');
        var waitingTimeout;

        scope.onKeepDrop = function (keeps) {
          waitingTimeout = $timeout(function () {
            scope.isWaiting = true;
          }, 500);
          scope.isDragTarget = false;
          tagService.addKeepsToTag(scope.tag, keeps).then(function () {
            $timeout.cancel(waitingTimeout);
            scope.isWaiting = false;
          });
        };

        scope.navigateToTag = function (event) {
          if (scope.isRenaming) {
            event.stopPropagation();
          } else {
            scope.viewTag({tagId: scope.tag.id});
          }
        };

        scope.setRenaming = function () {
          closeDropdown();
          scope.takeFocus();
          scope.isRenaming = true;
          scope.renameTag.value = scope.tag.name;
          $timeout(function () {
            input.focus();
            input.select();
          });
        };

        scope.remove = function () {
          closeDropdown();
          scope.removeTag({tag: scope.tag});
        };

        scope.onRenameKeydown = function (e) {
          switch (e.keyCode) {
            case keyIndices.KEY_ENTER:
              scope.submitRename();
              break;
            case keyIndices.KEY_ESC:
              scope.cancelRename();
              break;
          }
        };

        scope.submitRename = function () {
          var newName = scope.renameTag.value;
          if (newName && newName !== scope.tag.name) {
            return tagService.rename(scope.tag.id, newName).then(function () {
              scope.cancelRename();
            });
          }
          scope.cancelRename();
        };

        scope.cancelRename = function () {
          scope.isRenaming = false;
          scope.releaseFocus();
        };

        function closeDropdown() {
          scope.isDropdownOpen = false;
          $document.unbind('click', applyCloseDropdown);
        }

        function applyCloseDropdown() {
          scope.$apply(closeDropdown);
        }

        scope.toggleDropdown = function () {
          if (!scope.isDropdownOpen) {
            scope.isDropdownOpen = true;
            $document.bind('click', applyCloseDropdown);
          } else {
            closeDropdown();
          }
        };

        input.on('blur', function () {
          scope.$apply(function () { scope.cancelRename(); });
        });

        var keepDragMask = element.find('.kf-drag-mask');
        scope.isDragTarget = false;

        keepDragMask.on('dragenter', function () {
          scope.$apply(function () { scope.isDragTarget = true; });
        });

        keepDragMask.on('dragleave', function () {
          scope.$apply(function () { scope.isDragTarget = false; });
        });

        var tagDragMask = element.find('.kf-tag-drag-mask');
        var tagDraggedUpon = false;
        var yBoundary = parseInt(element.css('height'), 10) / 2;
        var isTop = null;

        function startTagDrag() {
          tagDraggedUpon = true;
          isTop = null;
        }

        function stopTagDrag() {
          tagDraggedUpon = false;
          tagDragMask.css({borderTopStyle: 'none', borderBottomStyle: 'none', margin: 0});
        }

        tagDragMask.on('dragenter', startTagDrag);
        tagDragMask.on('dragover', function (e) {
          if (tagDraggedUpon && scope.watchTagReorder()) {
            var posY = e.originalEvent.clientY - util.offset(element).top;
            if (posY > yBoundary) {
              isTop = false;
              tagDragMask.css({borderTopStyle: 'none', borderBottomStyle: 'dotted', marginTop: '1px'});
            } else {
              isTop = true;
              tagDragMask.css({borderTopStyle: 'dotted', borderBottomStyle: 'none', marginTop: 0});
            }
          }
        });
        tagDragMask.on('dragleave', stopTagDrag);

        scope.onTagDrop = function (tag) {
          stopTagDrag();
          if (isTop !== null && scope.watchTagReorder()) {
            // The "dragend" handler must be called before removing the element from the DOM.
            element.find('.kf-nav-link').triggerHandler('dragend');
            scope.reorderTag({isTop: isTop, srcTag: tag, dstTag: scope.tag});
          }
        };

        scope.isDragging = false;
        var clone;
        var mouseX, mouseY;
        element.bind('mousemove', function (e) {
          mouseX = e.pageX - util.offset(element).left;
          mouseY = e.pageY - util.offset(element).top;
        });
        element.bind('dragstart', function (e) {
          element.addClass('kf-dragged');
          clone = element.clone().css({
            position: 'absolute',
            left: 0,
            top: 0,
            width: element.css('width'),
            height: element.css('height'),
            zIndex: -1
          });
          element.parent().after(clone);
          e.dataTransfer.setDragImage(clone[0], mouseX, mouseY);
          scope.$apply(function () { scope.isDragging = true; });
        });
        element.bind('dragend', function () {
          element.removeClass('kf-dragged');
          clone.remove();
          scope.$apply(function () { scope.isDragging = false; });
        });

        var tagLink = element.find('.kf-nav-link');
        var tagInfo = element.find('.kf-tag-info');
        var tagName = element.find('.kf-tag-name');
        $timeout(function () {
          tagName.css({maxWidth: 0});
          tagName.css({maxWidth: (parseInt(tagLink.css('width'), 10) - parseInt(tagInfo.css('width'), 10)) + 'px'});
        });

        var newLocationMask = element.find('.kf-tag-new-location-mask');
        scope.$watch(scope.hasNewLocation, function (value) {
          if (value) {
            newLocationMask.removeClass('hidden');
            $timeout(function () {
              newLocationMask.addClass('hidden');
            });
          }
        });

        scope.enableHover = function () {
          scope.isHovering = true;
        };

        scope.disableHover = function () {
          scope.isHovering = false;
        };
      }
    };
  }
]);

'use strict';

angular.module('kifi.tagService', [
  'kifi.undo',
  'kifi.keepService',
  'kifi.routeService',
  'angulartics'
])

.factory('tagService', [
  '$http', 'env', '$q', '$rootScope', 'undoService', 'keepService', 'routeService', '$analytics',
  function ($http, env, $q, $rootScope, undoService, keepService, routeService, $analytics) {
    var list = [],
      tagsById = {},
      fetchAllPromise = null;

    function indexById(id) {
      for (var i = 0, l = list.length; i < l; i++) {
        if (list[i].id === id) {
          return i;
        }
      }
      return -1;
    }

    function updateKeepCount(id, delta) {
      var index = indexById(id);
      if (index !== -1) {
        var tag = list[index];
        tag.keeps = (tag.keeps || 0) + delta;
        return tag;
      }
      return null;
    }

    function addKeepsToTag(tag, keeps) {
      var url = env.xhrBase + '/keeps/add';
      var payload = {
        collectionId: tag.id,
        keeps: keeps
      };
      return $http.post(url, payload).then(function (res) {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'addKeepsToTag'
        });
        if (res.data && res.data.addedToCollection) {
          updateKeepCount(tag.id, res.data.addedToCollection);
          // broadcast change to interested parties
          keeps.forEach(function (keep) {
            $rootScope.$emit('tags.addToKeep', {tag: tag, keep: keep});
          });
        }
        return res;
      });
    }

    function persistOrdering() {
      $http.post(routeService.tagOrdering, _.pluck(list, 'id')).then(function () {
      });
      api.fetchAll();
    }

    function reorderTag(isTop, srcTag, dstTag) {
      // isTop indicates whether dstTag should be placed before or after srcTag
      var index = _.findIndex(list, function (tag) { return tag.id === dstTag.id; });
      var newSrcTag = _.clone(srcTag);
      var srcTagId = srcTag.id;
      newSrcTag.id = -1;
      if (!isTop) {
        index += 1;
      }
      list.splice(index, 0, newSrcTag);
      _.remove(list, function (tag) { return tag.id === srcTagId; });
      for (var i = 0; i < list.length; i++) {
        if (list[i].id === -1) {
          list[i].id = srcTagId;
        }
      }
      persistOrdering();
      $analytics.eventTrack('user_clicked_page', {
        'action': 'reorderTag'
      });
    }

    var api = {
      list: list,

      getById: function (tagId) {
        return tagsById[tagId] || null;
      },

      promiseById: function (tagId) {
        return api.fetchAll().then(function () {
          return api.getById(tagId);
        });
      },

      fetchAll: function (force) {
        if (!force && fetchAllPromise) {
          return fetchAllPromise;
        }

        var url = env.xhrBase + '/collections/all';
        var config = {
          params: {
            sort: 'user',
            _: Date.now().toString(36)
          }
        };

        fetchAllPromise = $http.get(url, config).then(function (res) {
          var tags = res.data && res.data.collections || [];
          list.length = 0;
          list.push.apply(list, tags.slice(0, 40));

          list.forEach(function (tag) {
            tagsById[tag.id] = tag;
          });

          keepService.totalKeepCount = res.data.keeps; // a bit weird...

          return list;
        });

        return fetchAllPromise;
      },

      create: function (name) {
        var url = env.xhrBase + '/collections/create';

        return $http.post(url, {
          name: name
        }).then(function (res) {
          var tag = res.data;
          tag.keeps = tag.keeps || 0;
          list.unshift(tag);
          $analytics.eventTrack('user_clicked_page', {
            'action': 'createTag'
          });
          return tag;
        });
      },

      remove: function (tag) {
        var url = env.xhrBase + '/collections/' + tag.id + '/delete';
        return $http.post(url).then(function () {
          var index = indexById(tag.id);
          if (index !== -1) {
            list.splice(index, 1);
          }
          $rootScope.$emit('tags.remove', tag.id);
          $analytics.eventTrack('user_clicked_page', {
            'action': 'removeTag'
          });
          undoService.add('Tag deleted.', function () {
            api.unremove(tag, index);
          });
          return tag;
        });
      },

      unremove: function (tag, index) {
        var url = env.xhrBase + '/collections/' + tag.id + '/undelete';
        return $http.post(url).then(function () {
          if (index !== -1) {
            list.splice(index, 0, tag);
          }
          $rootScope.$emit('tags.unremove', tag.id);
          $analytics.eventTrack('user_clicked_page', {
            'action': 'unremoveTag'
          });
          persistOrdering();
          return tag;
        });
      },

      rename: function (tagId, name) {
        function renameTag(id, name) {
          var index = indexById(id);
          if (index !== -1) {
            var tag = list[index];
            tag.name = name;
            return tag;
          }
          return null;
        }

        var url = env.xhrBase + '/collections/' + tagId + '/update';
        return $http.post(url, {
          name: name
        }).then(function (res) {
          var tag = res.data;
          $analytics.eventTrack('user_clicked_page', {
            'action': 'renameTag'
          });
          return renameTag(tag.id, tag.name);
        });
      },

      removeKeepsFromTag: function (tagId, keepIds) {
        var url = env.xhrBase + '/collections/' + tagId + '/removeKeeps';
        $http.post(url, keepIds).then(function (res) {
          updateKeepCount(tagId, -keepIds.length);
          // broadcast change to interested parties
          keepIds.forEach(function (keepId) {
            $rootScope.$emit('tags.removeFromKeep', {tagId: tagId, keepId: keepId});
          });
          $analytics.eventTrack('user_clicked_page', {
            'action': 'removeKeepsFromTag'
          });
          return res;
        });
      },

      addKeepsToTag: addKeepsToTag,

      addKeepToTag: function (tag, keep) {
        return addKeepsToTag(tag, [keep]);
      },

      reorderTag: reorderTag
    };

    return api;
  }
]);

'use strict';

angular.module('kifi.tags', ['util', 'dom', 'kifi.tagService', 'kifi.tagItem'])

.controller('TagsCtrl', [
  '$scope', '$timeout', 'tagService',
  function ($scope, $timeout, tagService) {
    $scope.create = function (name) {
      if (name) {
        return tagService.create(name)
          .then(function (tag) {
            tag.isNew = true;
            $scope.clearFilter();

            $timeout(function () {
              delete tag.isNew;
            }, 3000);

            return tag;
          });
      }
    };
  }
])

.directive('kfTags', [
  '$timeout', '$window', '$rootScope', '$location', 'util', 'dom', 'tagService', 'profileService',
  function ($timeout, $window, $rootScope, $location, util, dom, tagService, profileService) {
    var KEY_UP = 38,
      KEY_DOWN = 40,
      KEY_ENTER = 13,
      KEY_ESC = 27,
      //KEY_TAB = 9,
      KEY_DEL = 46,
      KEY_F2 = 113;

    return {
      restrict: 'A',
      templateUrl: 'tags/tags.tpl.html',
      scope: {},
      controller: 'TagsCtrl',
      link: function (scope, element) {
        scope.tags = tagService.list;
        scope.newLocationTagId = null;
        scope.viewedTagId = null;

        scope.clearFilter = function (focus) {
          scope.filter.name = '';
          if (focus) {
            scope.focusFilter = true;
          }
        };

        scope.unfocus = function () {
          scope.lastHighlight = scope.highlight;
        };

        scope.refocus = function () {
          if (scope.lastHighlight && !scope.highlight) {
            scope.highlight = scope.lastHighlight;
          }
          scope.lastHighlight = null;
          scope.focusFilter = true;
        };

        function getFilterValue() {
          return scope.filter && scope.filter.name || '';
        }

        scope.showAddTag = function () {
          var name = getFilterValue(),
            res = false;
          if (name) {
            name = name.toLowerCase();
            res = !scope.tags.some(function (tag) {
              return tag.name.toLowerCase() === name;
            });
          }
          scope.isAddTagShown = res;
          return res;
        };

        scope.isActiveTag = function (tag) {
          return util.startsWith($location.path(), '/tag/' + tag.id);
        };

        scope.getShownTags = function () {
          var child = scope.$$childHead;
          while (child) {
            if (child.shownTags) {
              return child.shownTags;
            }
            child = child.$$nextSibling;
          }
          return scope.tags || [];
        };

        function indexOfTag(tag) {
          if (tag) {
            return scope.getShownTags().indexOf(tag);
          }
          return -1;
        }

        scope.viewTag = function (tagId) {
          if (tagId) {
            scope.viewedTagId = tagId;
            return $location.path('/tag/' + tagId);
          }
        };

        scope.select = function () {
          if (scope.highlight) {
            return scope.viewTag(scope.highlight.id);
          }
          return scope.create(getFilterValue());
        };

        scope.onKeydown = function (e) {
          switch (e.keyCode) {
          case KEY_UP:
            scope.highlightPrev();
            break;
          case KEY_DOWN:
            scope.highlightNext();
            break;
          case KEY_ENTER:
            scope.select();
            break;
          case KEY_ESC:
            if (scope.highlight) {
              scope.dehighlight();
            }
            else {
              scope.clearFilter();
            }
            break;
          case KEY_DEL:
            scope.remove(scope.highlight);
            break;
          case KEY_F2:
            scope.rename(scope.highlight);
            break;
          }
        };

        scope.refreshHighlight = function () {
          var shownTags = scope.getShownTags();
          var highlight = scope.highlight;
          if (highlight) {
            var index = shownTags.indexOf(highlight);
            if (index !== -1) {
              // might scroll
              return scope.highlightAt(index);
            }
          }

          if (getFilterValue() && shownTags.length) {
            return scope.highlightFirst();
          }

          return scope.dehighlight();
        };

        scope.isHighlight = function (tag) {
          return scope.highlight === tag;
        };

        scope.isHighlightNew = function () {
          return !scope.highlight && !! getFilterValue();
        };

        scope.dehighlight = function () {
          scope.highlight = null;
          if (scope.isAddTagShown) {
            dom.scrollIntoViewLazy(element.find('.kf-tag-new')[0]);
          }
          return null;
        };

        scope.highlightAt = function (index) {
          if (index == null) {
            return scope.dehighlight();
          }

          var tags = scope.getShownTags(),
            len = tags.length;
          if (!len) {
            return scope.dehighlight();
          }

          index = ((index % len) + len) % len;
          var tag = tags[index];
          scope.highlight = tag;
          dom.scrollIntoViewLazy(element.find('.kf-tag')[index]);
          return tag;
        };

        scope.highlightFirst = function () {
          return scope.highlightAt(0);
        };

        scope.highlightLast = function () {
          return scope.highlightAt(-1);
        };

        scope.highlightNext = function () {
          if (scope.isHighlightNew()) {
            // new tag is highlighted
            // highlight the first
            return scope.highlightFirst();
          }

          var index = indexOfTag(scope.highlight);
          if (index === -1) {
            // no highlight
            // highlight the first
            return scope.highlightFirst();
          }

          if (index === scope.getShownTags().length - 1) {
            // last item on the list

            if (scope.isAddTagShown) {
              // highlight the new tag if available
              return scope.dehighlight();
            }

            // the first, otherwise
            return scope.highlightFirst();
          }

          // highlight the next item
          return scope.highlightAt(index + 1);
        };

        scope.highlightPrev = function () {
          if (scope.isHighlightNew()) {
            // new tag is highlighted
            // highlight the last
            return scope.highlightLast();
          }

          var index = indexOfTag(scope.highlight);
          if (index === -1) {
            // no highlight
            // highlight the last
            return scope.highlightLast();
          }

          if (index === 0) {
            // first item on the list

            if (scope.isAddTagShown) {
              // highlight the new tag if available
              return scope.dehighlight();
            }

            // the last, otherwise
            return scope.highlightLast();
          }

          // highlight the prev item
          return scope.highlightAt(index - 1);
        };

        var list = element.find('.kf-tag-list');
        var hidden = element.find('.kf-tag-list-hidden');

        function positionTagsList() {
          list.css({
            position: 'absolute',
            top: hidden.position().top,
            bottom: 0
          });
        }
        $timeout(positionTagsList);

        scope.$watch(function () {
          return profileService.me.seqNum;
        }, function () {
          // This is a bit hacky, would love to improve.
          // Normally, we can position the tags list immediately (and doing so
          // avoids a reflow flash). However, when `me` comes in too slow,
          // if we run positionTagsList synchronously, it's too soon.

          // I still don't like it because we can still hit the reflow flash.

          positionTagsList();
          $timeout(positionTagsList); // use $timeout so that `me` is drawn first, before resizing tags
        });

        angular.element($window).resize(_.throttle(function () {
          positionTagsList();
          scope.refreshScroll();
        }, 150));

        scope.$watch('filter.name', function () {
          $timeout(scope.refreshHighlight);
          scope.refreshScroll();
        });

        scope.$watch('tags.length', function () {
          scope.refreshScroll();
        });

        tagService.fetchAll();

        scope.watchTagReorder = function () {
          return !getFilterValue();
        };

        scope.reorderTag = function (isTop, srcTag, dstTag) {
          tagService.reorderTag(isTop, srcTag, dstTag);
          scope.newLocationTagId = srcTag.id;
        };

        scope.removeTag = function (tag) {
          return tagService.remove(tag).then(function () {
            if (scope.viewedTagId === tag.id) {
              scope.viewedTagId = null;
              $location.path('/');
            }
          });
        };
      }
    };
  }
]);

angular.module('kifi.templates', ['common/directives/alertBanner/alertBanner.tpl.html', 'common/directives/keepWho/friendCard.tpl.html', 'common/directives/keepWho/keepWhoPic.tpl.html', 'common/directives/keepWho/keepWhoPics.tpl.html', 'common/directives/keepWho/keepWhoText.tpl.html', 'common/modal/basicModalContent.tpl.html', 'common/modal/modal.tpl.html', 'detail/detail.tpl.html', 'detail/keepDetail.tpl.html', 'detail/tagList.tpl.html', 'friends/friendCard.tpl.html', 'friends/friendRequestCard.tpl.html', 'friends/friends.tpl.html', 'home/emailModal.tpl.html', 'home/home.tpl.html', 'invite/connectionCard.tpl.html', 'invite/invite.tpl.html', 'invite/inviteSearch.tpl.html', 'invite/inviteWell.tpl.html', 'keep/addKeepsModal.tpl.html', 'keep/importModal.tpl.html', 'keep/keep.tpl.html', 'keeps/keeps.tpl.html', 'layout/footer/footer.tpl.html', 'layout/leftCol/leftCol.tpl.html', 'layout/main/main.tpl.html', 'layout/nav/nav.tpl.html', 'layout/rightCol/rightCol.tpl.html', 'profile/emailImport.tpl.html', 'profile/profile.tpl.html', 'profile/profileChangePassword.tpl.html', 'profile/profileEmailAddresses.tpl.html', 'profile/profileImage.tpl.html', 'profile/profileInput.tpl.html', 'profileCard/profileCard.tpl.html', 'search/search.tpl.html', 'social/addNetworksModal.tpl.html', 'social/connectNetworks.tpl.html', 'social/networksNeedAttention.tpl.html', 'tagKeeps/tagKeeps.tpl.html', 'tags/tagItem.tpl.html', 'tags/tags.tpl.html']);

angular.module('common/directives/alertBanner/alertBanner.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('common/directives/alertBanner/alertBanner.tpl.html',
    '<div class="kf-alert-banner"><span class="kf-alert-banner-body"><span class="kf-alert-banner-head">Important -</span> <span ng-transclude=""></span></span> <span class="kf-alert-banner-button" ng-if="actionText" ng-click="action()">{{actionText}}</span></div>');
}]);

angular.module('common/directives/keepWho/friendCard.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('common/directives/keepWho/friendCard.tpl.html',
    '<div class="kifi-fr-kcard tooltip-show-hide" ng-show="tooltipEnabled"><div class="kifi-fr-kcard-top"><img class="kifi-fr-kcard-pic" ng-src="{{getPicUrl(keeper)}}"><div class="kifi-fr-kcard-info"><div class="kifi-fr-kcard-name">{{getName(keeper)}}</div><div class="kifi-fr-kcard-desc">Your kifi friend</div></div></div><div class="kifi-fr-kcard-tri"></div></div>');
}]);

angular.module('common/directives/keepWho/keepWhoPic.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('common/directives/keepWho/keepWhoPic.tpl.html',
    '<a class="kf-keep-who-pic" href="javascript:" ng-attr-style="background-image: url({{getPicUrl(keeper)}})" ng-mouseenter="showTooltip()" ng-mouseleave="hideTooltip()" ng-click="hideTooltip()"></a>');
}]);

angular.module('common/directives/keepWho/keepWhoPics.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('common/directives/keepWho/keepWhoPics.tpl.html',
    '<span class="kf-keep-who-pics"><a class="kf-keep-who-pic me" title="You! You look great!" ng-attr-style="background-image: url({{getPicUrl(me)}})" ng-if="keep.isMyBookmark"></a><a kf-keep-who-pic="" keeper="keeper" ng-repeat="keeper in keepers | limitTo: 9"></a></span>');
}]);

angular.module('common/directives/keepWho/keepWhoText.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('common/directives/keepWho/keepWhoText.tpl.html',
    '<span class="kf-keep-who-text"><span class="kf-keep-you" ng-if="keep.isMyBookmark">You <span class="kf-keep-private" ng-class="{ on: isPrivate() }" ng-show="hasKeepers() || hasOthers()">Private</span></span> <span class="kf-keep-friends" ng-show="hasKeepers()">{{getFriendText()}}</span> <span></span> <span class="kf-keep-others" ng-show="hasOthers()">{{getOthersText()}}</span> <span class="kf-keep-kept-this">kept this</span> <span class="kf-keep-private" ng-class="{ on: isPrivate() }" ng-if="!hasKeepers() && !hasOthers()">Private</span></span>');
}]);

angular.module('common/modal/basicModalContent.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('common/modal/basicModalContent.tpl.html',
    '<div class="dialog-content"><div class="dialog-title">Kifi</div><a class="dialog-x" ng-click="hideAndCancel()">✕</a><div class="dialog-body-wrap"><div class="dialog-header" ng-if="title.length" ng-bind-html="title"></div><div class="dialog-body" ng-transclude="" ng-class="{\'dialog-centered\': centered}"></div></div><div class="dialog-buttons" ng-if="singleAction"><button class="dialog-cancel" ng-click="hideAndCancel()" ng-if="withCancel">{{cancelText || \'Cancel\'}}</button> <button ng-class="{\'dialog-warn\': withWarning}" ng-click="hideAndAction()">{{actionText || \'Ok\'}}</button></div></div>');
}]);

angular.module('common/modal/modal.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('common/modal/modal.tpl.html',
    '<div ng-show="show"><div class="modal-backdrop fade in" ng-click="hideModal()" ng-style="backdropStyle"></div><div tabindex="-1" index="0" class="modal-dialog" ng-style="dialogStyle"><div class="modal-content" ng-transclude=""></div></div></div>');
}]);

angular.module('detail/detail.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('detail/detail.tpl.html',
    '<div class="kf-detail-pane" ng-show="isDetailOpen()"><div class="kf-detail-scroll" antiscroll="{ autoHide: false }"><div class="kf-detail-inner" ng-class="{ single: isSingleKeep(), multiple: !isSingleKeep(), private: isPrivate(), public: isPublic() }"><a class="kf-detail-x" href="javascript:" ng-click="closeDetail()"></a><div class="kf-page-meta" data-n="{{getLength()}}"><div kf-keep-detail="" ng-if="isSingleKeep()"></div><h2 class="kf-detail-header page-title" ng-hide="isSingleKeep()" ng-bind="getTitleText()"></h2><a class="kf-page-keep" href="javascript:" ng-click="toggleKeep()"></a> <a class="kf-page-priv" href="javascript:" ng-click="togglePrivate()"></a></div><div ng-if="isSingleKeep()"><div class="kf-page-who"><h2 class="kf-detail-header">Who kept this:</h2><div class="kf-page-who-pics"><span kf-keep-who-pics="" me="me" keepers="keep.keepers" keep="keep"></span></div><div class="kf-page-who-text" kf-keep-who-text="" keep="keep"></div></div><div class="kf-page-chatter"><h2 class="kf-detail-header">Talking about this Keep:</h2><a class="kf-page-chatter-messages" href="{{url}}" target="_blank" data-locator="/messages"><span class="chatter-count">{{keep.conversationCount || 0}}</span>{{getPrivateConversationText()}}</a></div></div><span kf-tag-list="" keep="keep" get-selected-keeps="getSelectedKeeps()"></span></div></div></div>');
}]);

angular.module('detail/keepDetail.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('detail/keepDetail.tpl.html',
    '<div class="kf-keep-detail"><h2 class="kf-detail-header page-title" ng-bind="keep.title"></h2><a class="kf-keep-detail-url long-text" ng-href="{{keep.url}}" target="_blank">{{keep.url}}</a><div class="kf-page-pic-wrap"><div class="kf-page-pic-special" ng-class="{ \'kf-page-pic-special-cell\': keep.isEmbed }" ng-if="keep.isEmbed"><div kf-youtube="" video-id="keep.videoId"></div></div><a class="kf-page-pic" ng-if="!keep.isEmbed" ng-href="{{keep.url}}" target="_blank" ng-attr-style="background-image: url({{keep.screenshot}})"><div class="kf-page-pic-1"><div class="kf-page-pic-2"><div class="kf-page-pic-3"><div class="kf-page-pic-soon">Preview of this page<br>not yet available</div><span class="kf-page-pic-tip">Visit page</span></div></div></div></a><div class="kf-page-how"><div class="kf-page-how-0"><div class="kf-page-how-pub"><div class="kf-page-how-1"><div class="kf-page-how-2"><div class="kf-page-how-3">Public</div></div></div></div><div class="kf-page-how-pri"><div class="kf-page-how-1"><div class="kf-page-how-2"><div class="kf-page-how-3">Private</div></div></div></div></div></div></div></div>');
}]);

angular.module('detail/tagList.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('detail/tagList.tpl.html',
    '<div class="page-colls"><h2 class="kf-detail-header" ng-show="commonTags.length > 0">Tags:</h2><ul class="page-coll-list"><li class="page-coll" data-id="{{tag.id}}" ng-repeat="tag in commonTags"><a class="page-coll-a" href="/tag/{{tag.id}}">{{tag.name}}</a> <a class="page-coll-x" href="javascript:" ng-click="removeTagFromSelectedKeeps(tag)">×</a></li><li class="page-coll-new"><span ng-show="!isAddingTag"><a class="page-coll-add-circle" href="javascript:" ng-show="hasTags()" ng-click="showAddTagDropdown()">+</a> <a class="page-coll-add" href="javascript:" ng-show="!hasTags()" ng-click="showAddTagDropdown()"><span class="page-coll-add-circle">+</span> {{addTagLabel()}}</a></span> <span ng-show="isAddingTag" kf-tag-suggestions=""><input focus-when="shouldGiveFocus" class="page-coll-input" type="text" placeholder="tag name" ng-model="tagFilter.name" ng-keydown="onKeydown($event)" ng-blur="hideAddTagDropdown()"><ul class="page-coll-opts"><li class="page-coll-opt" ng-repeat="tag in tagTypeAheadResults" ng-class="{ current: isTagHighlighted(tag) }" ng-mousedown="addTag(tag, keep)" ng-bind-html="tag.prettyHtml" ng-mouseover="highlightTag(tag)"></li><li class="page-coll-opt" ng-if="isAddTagShown()" ng-mousedown="createAndAddTag(keep)" ng-class="{ current: isTagHighlighted(null) }" ng-mouseover="highlightTag(null)">{{tagFilter.name}}<span class="new-label">{{newTagLabel}}</span></li></ul><span class="page-coll-opt-hidden"></span></span></li></ul></div>');
}]);

angular.module('friends/friendCard.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('friends/friendCard.tpl.html',
    '<div class="kf-friend-card"><img class="kf-friend-card-image" onerror="this.src=\'https://www.kifi.com/assets/img/ghost.100.png\'" ng-src="{{mainImage}}"><span class="kf-friend-card-who"><div class="kf-friend-card-name" title="{{name}}">{{name}}</div><div class="kf-friend-card-count" title="{{friendCount}}">{{friendCount}} friends on Kifi</div></span> <span class="kf-friend-card-button">Friends</span><ul class="kf-dropdown-menu kf-friend-card-menu"><li ng-show="searchFriend"><a href="javascript:" ng-click="unsearchfriend()">Hide {{possesive}} keeps in my search results</a></li><li ng-show="!searchFriend"><a href="javascript:" ng-click="researchfriend()">Show {{possesive}} keeps in my search results</a></li><li><a href="javascript:" ng-click="unfriend()">Unfriend</a></li></ul><div kf-modal="" show="showUnfriendConfirm" class="kf-unfriend-confirm-modal"><div kf-basic-modal-content="" with-cancel="true" title="Unfriend {{name}}?" with-warning="" action="reallyUnfriend()">Are you sure you want to unfriend this user?<ul><li>You will not see each other\'s results</li><li>You will not be able to send messages to each other</li></ul></div></div></div>');
}]);

angular.module('friends/friendRequestCard.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('friends/friendRequestCard.tpl.html',
    '<div class="kf-friend-card kf-friend-request-card"><img class="kf-friend-card-image" onerror="this.src=\'https://www.kifi.com/assets/img/ghost.100.png\'" ng-src="{{mainImage}}"><span class="kf-friend-card-who"><div class="kf-friend-card-name" title="{{name}}">{{name}}</div><div>Accept as your Kifi friend?</div></span> <span class="kf-friend-request-card-buttons"><span class="kf-accept" ng-click="accept()">Accept</span> <span class="kf-ignore" ng-click="ignore()">Ignore<span></span></span></span></div>');
}]);

angular.module('friends/friends.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('friends/friends.tpl.html',
    '<div ng-controller="FriendsCtrl"><div class="friends-wrapper" antiscroll="{ autoHide: false }"><div class="friends"><h2>Kifi Friends <span ng-show="friends.length > 0">({{friends.length}})</span></h2><p>Search your friends list by name or email address.</p><div class="import-wrapper" ng-if="isRefreshingSocialGraph"><div class="bouncy-ball"><div class="pace pace-active"><div class="pace-progress"></div><div class="pace-activity"></div></div></div>Importing your contacts</div><div kf-social-invite-well=""></div><div kf-networks-need-attention=""></div><div class="friend-requests" ng-show="requests.length > 0" ng-class="\'unfold\'"><div class="friends-list-header"><span class="friend-requests-badge">{{requests.length || 0}}</span> Pending Friend <span ng-pluralize="" count="requests.length" when="{\'one\': \'Request\', \'other\': \'Requests\'}"></span> <span ng-show="requests.length>2">| <a href="javascript:" ng-click="toggleRequestExpansion()">{{requestsToggleText}}</a></span></div><div kf-friend-request-card="" ng-repeat="request in requests | limitTo: requestsToShow" request="request"></div></div><div class="friends-list" ng-show="friends.length > 0"><div class="friends-list-header">You have {{friends.length}} Kifi friends | <a href="/invite">Invite more friends</a></div><div kf-friend-card="" ng-repeat="friend in friends" friend="friend"></div></div><div ng-if="friends.length <= 10" class="social-connect-friends"><div kf-social-connect-networks=""></div></div></div></div></div>');
}]);

angular.module('home/emailModal.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('home/emailModal.tpl.html',
    '<div kf-modal="" show="data.showEmailModal"><div kf-basic-modal-content="" centered="true" title="Email verified" action-text="Continue">Thanks for verifying your email address.</div></div>');
}]);

angular.module('home/home.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('home/home.tpl.html',
    '<div><div ng-if="modal"><div ng-if="data.showEmailModal" ng-include="\'home/emailModal.tpl.html\'"></div></div><div class="kf-main-head"><h1 class="kf-main-title">Browse your Keeps</h1><div class="kf-subtitle"><span class="kf-check-all" ng-class="{ enabled: isCheckEnabled(), checked: isSelectedAll(),  \'multi-checked\': isMultiChecked() }" ng-click="toggleSelectAll()" ng-mouseover="onMouseoverCheckAll()" ng-mouseout="onMouseoutCheckAll()"></span> <span class="kf-subtitle-text">{{getSubtitle()}}</span></div></div><div kf-keeps="" keeps="keeps" keeps-loading="loading" keeps-has-more="hasMore()" scroll-distance="scrollDistance" scroll-disabled="scrollDisabled" scroll-next="getNextKeeps()"></div></div>');
}]);

angular.module('invite/connectionCard.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('invite/connectionCard.tpl.html',
    '<div class="kf-connection-card" ng-class="{\'kf-connection-invited\': invited}" ng-hide="hidden"><img class="kf-connection-card-image" onerror="this.src=\'https://www.kifi.com/assets/img/ghost.100.png\'" ng-src="{{mainImage}}" ng-class="{\'kf-connection-card-email-image\': email}"><span class="kf-connection-card-who"><span class="kf-main-label" title="{{mainLabel}}">{{mainLabel}}</span> <span class="kf-byline-icon kf-icon-micro" ng-hide="email" ng-class="{\'kf-facebook-icon-micro\': facebook, \'kf-linkedin-icon-micro\': linkedin}"></span> <span class="kf-byline" title="{{byline}}">{{byline}}</span> <span class="kf-byline">{{byline2}}</span></span> <span class="kf-connection-card-action" ng-class="{\'kf-connection-invited\': invited}" ng-click="action()">{{actionText}}</span> <span class="kf-connection-card-close" ng-click="closeAction()">✕</span></div>');
}]);

angular.module('invite/invite.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('invite/invite.tpl.html',
    '<div ng-controller="InviteCtrl"><div class="invite-wrapper" antiscroll="{ autoHide: false }"><div class="invite"><h2>Find people to invite to Kifi</h2><p>Search by name or email address</p><div class="import-wrapper" ng-if="isRefreshingSocialGraph"><div class="bouncy-ball"><div class="pace pace-active"><div class="pace-progress"></div><div class="pace-activity"></div></div></div>Importing your contacts</div><div kf-social-invite-well=""></div><div kf-networks-need-attention=""></div><h3>People you may want to add to Kifi</h3><div class="kf-connection-card-container" smart-scroll="" scroll-distance="wtiScrollDistance" scroll-disabled="isWTIScrollDisabled()" scroll-next="wtiScrollNext()"><div kf-connection-card="" ng-repeat="friend in whoToInvite" friend="friend" refresh-scroll="refreshScroll" show-generic-invite-error="data.showGenericInviteError" show-linkedin-token-expired-modal="data.showLinkedinTokenExpiredModal" show-linkedin-hit-rate-limit-modal="data.showLinkedinHitRateLimitModal"></div><span ng-click="wtiScrollNext()" ng-hide="isWTIScrollDisabled()" class="kf-show-more">Show more</span></div><div ng-if="whoToInvite.length < 6 && wtiLoaded" class="social-connect-friends"><div kf-social-connect-networks=""></div></div><div kf-modal="" show="data.showGenericInviteError"><div kf-basic-modal-content="" action-text="Ok" title="Sorry, an error occurred, please try again later."></div></div><div kf-modal="" show="data.showLinkedinHitRateLimitModal"><div kf-basic-modal-content="" action-text="Ok"><h3>LinkedIn limits the number of messages users can send through Kifi each day.</h3><h3>Please retry inviting this connection again, tomorrow.</h3></div></div><div kf-modal="" show="data.showLinkedinTokenExpiredModal"><div kf-basic-modal-content="" with-cancel="" action-text="Ok" action="showAddNetworksModal()" title="Your session has expired, you need to reconnect to LinkedIn."></div></div></div></div></div>');
}]);

angular.module('invite/inviteSearch.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('invite/inviteSearch.tpl.html',
    '<div class="social-invite-search"><input type="text" ng-model="search.name" placeholder="Find friends to add to Kifi" ng-change="change($event)"><ul class="social-invite-dropdown" ng-show="search.showDropdown"><li class="social-invite-element" ng-repeat="result in results" ng-class="\'social-invite-{{result.status === \'joined\' && result.network === \'fortytwoNF\' ? \'\' : result.status}}\'"><div ng-if="!result.custom"><div ng-if="result.networkType !== \'email\'"><img ng-src="{{result.image || \'https://www.kifi.com/assets/img/ghost.200.png\'}}" class="social-invite-image"></div><div ng-if="!result.status"><div class="social-invite-summary"><div class="social-invite-name">{{result.label}}</div><div class="social-invite-network"><span class="social-network-icon kf-icon-micro" ng-class="result.iconStyle"></span>{{result.network}}</div></div><div class="social-invite-action clickable"><div class="social-invite-button" ng-click="invite(result, $event)">Add</div></div></div><div ng-if="result.status === \'joined\' && result.networkType === \'fortytwo\'"><div class="social-invite-summary"><div class="social-invite-name">{{result.label}}</div><div class="social-invite-network">Your friend on Kifi</div></div></div><div ng-if="result.status === \'requested\' && result.networkType === \'fortytwoNF\'"><div class="social-invite-summary"><div class="social-invite-name">{{result.label}}</div><div class="social-invite-network">Kifi user — request sent</div></div></div><div ng-if="result.status === \'joined\' && result.networkType === \'fortytwoNF\'"><div class="social-invite-summary"><div class="social-invite-name">{{result.label}}</div><div class="social-invite-network">Kifi user</div></div><div class="social-invite-action clickable"><div class="social-invite-button" ng-click="invite(result, $event)">Add</div></div></div><div ng-if="result.status === \'invited\'"><div class="social-invite-summary"><div class="social-invite-name-long">{{result.label}}</div><div class="social-invite-network"><span class="social-network-icon kf-icon-micro" ng-class="result.iconStyle"></span>{{result.inviteText}}</div></div><div class="social-invite-action clickable"><div class="social-invite-button" ng-click="invite(result, $event)">Resend</div></div></div></div><div ng-if="result.custom === \'email\'"><img src="/img/email-icon.png" class="social-invite-image cant-find"><div class="social-invite-summary"><div class="social-invite-name">Email to</div><div class="social-invite-network">{{result.label}}</div></div><div class="social-invite-action clickable"><div class="social-invite-button" ng-click="invite(result, $event)">Add</div></div></div><div ng-if="result.custom === \'cant_find\'" class="fill-parent clickable" ng-click="data.showCantFindModal = true"><img src="/img/friend-missing@2x.png" class="social-invite-image cant-find"><div class="social-invite-summary"><div class="social-invite-name social-invite-custom-email">Can\'t find your friend?</div></div></div><div class="clearfix"></div></li></ul><div kf-modal="" show="data.showCantFindModal" kf-width="670px"><div kf-basic-modal-content="" single-action="false"><div class="friend-not-found-modal"><div class="friend-not-found-heading">We couldn\'t find any friends matching</div><div class="friend-not-found-name">\'{{search.name}}\'</div><div class="friend-not-found-sub-heading">How to fix it</div><ul><li ng-if="!hasNetworks()" class="friend-not-found-suggestion">You don\'t have any networks connected to Kifi yet. <a class="friend-not-found-link" href="javascript:" ng-click="connectNetworks()">Get started by connecting.</a></li><li ng-if="hasNetworks()" class="friend-not-found-suggestion">Your friends list might not be up to date. <a class="friend-not-found-link" href="javascript:" ng-click="refreshFriends()">Try refreshing it.</a></li><li ng-if="hasNetworks()" class="friend-not-found-suggestion">Some of your friends may have privacy settings that prevent us from showing them.<br>Tell them to go to kifi.com and sign up.</li><li class="friend-not-found-suggestion">Or just <a class="friend-not-found-link" href="http://support.kifi.com/customer/portal/emails/new">contact us.</a></li></ul><div class="dialog-buttons"><button class="friend-not-found-modal-done" ng-click="data.showCantFindModal = false">Done</button></div></div></div></div></div>');
}]);

angular.module('invite/inviteWell.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('invite/inviteWell.tpl.html',
    '<div class="social-invite-well"><span kf-social-invite-search=""></span> <span class="social-network-display-wrapper"><span class="social-networks-display"><span class="social-networks-summary">{{networkText}}</span> <span class="social-networks-add" ng-click="showAddNetworks()">Add new</span></span></span><div class="clearfix"></div></div>');
}]);

angular.module('keep/addKeepsModal.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('keep/addKeepsModal.tpl.html',
    '<div kf-modal="" ng-if="data.showAddKeeps" show="data.showAddKeeps" kf-width="450px"><div kf-basic-modal-content="" centered="true" action-text="Done"><div contenteditable="" class="add-keep-paste-bin">Paste URLs to keep here <span class="add-keep-isolated">http://www.kifi.com</span></div></div></div>');
}]);

angular.module('keep/importModal.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('keep/importModal.tpl.html',
    '<div kf-modal="" show="data.showImportModal" kf-width="540px" class="import-modal"><div kf-basic-modal-content="" centered="true" title="Privately add your bookmarks to Kifi?" single-action="false"><img src="/img/bookmark-to-kifi@2x.png" class="import-star-to-kifi"><p>Your bookmarks will be securely kept as private keeps and will never appear in your friends’ search results.</p><p>You also can find, organize, and remove them anytime at Kifi.com.</p><div class="dialog-buttons"><a class="dialog-cancel" ng-click="data.showImportModal = false" href="javascript:">Cancel</a> <button ng-click="importBookmarks()">Import bookmarks</button></div></div></div><div kf-modal="" show="data.showImportModal2" kf-width="540px" class="import-modal"><div kf-basic-modal-content="" centered="true" title="Importing your bookmarks to Kifi" action-text="Awesome"><img src="/img/bookmark-to-kifi@2x.png" class="import-star-to-kifi"><p>This might take a few minutes (especially if you have a lot of bookmarks). Feel free to browse around — we’re syncing in the background.</p></div></div><div kf-modal="" show="data.showImportError" kf-width="540px" class="import-modal"><div kf-basic-modal-content="" title="Whoops! We\'re having a problem." action-text="Done"><p>We\'re having trouble importing your bookmarks because it seems that you aren\'t running an up-to-date Kifi extension.</p><p>If you think this is an error, <a href="http://support.kifi.com/customer/portal/emails/new">contact support</a> and we\'ll sort things out. Thanks!</p></div></div>');
}]);

angular.module('keep/keep.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('keep/keep.tpl.html',
    '<li class="kf-keep" ng-click="clickAction({keep: keep, event: $event})" ng-hide="keep.unkept" ui-draggable="true" drag-channel="keepDragChannel" ui-on-drop="onTagDrop($data)" drop-channel="tagDragChannel" drag-enter-class="kf-candidate-drag-target" ng-class="{ \'kf-dragged\': isDragging, \'kf-drag-target\': isDragTarget }"><time class="kf-keep-time" ng-attr-datetime="{{keep.createdAt}}" ng-show="keep.createdAt" am-time-ago="keep.createdAt"></time><div class="kf-keep-handle"><div class="kf-keep-checkbox" ng-click="onCheck($event)"></div></div><div class="kf-keep-title"><span class="kf-example-tag">Example keep</span> <a class="kf-keep-title-link" ng-href="{{keep.url}}" target="_blank" ng-attr-title="{{getTitle()}}" ng-bind-html="keep.titleHtml"></a></div><div class="kf-keep-url" ng-attr-title="{{keep.url}}" ng-bind-html="keep.descHtml"></div><div class="kf-keep-tags"><span class="kf-keep-tag" ng-class="{ example: isExampleTag(tag) }" ng-repeat="tag in getTags()"><a class="kf-keep-tag-link" ng-href="/tag/{{tag.id}}">{{tag.name}}</a></span></div><div class="kf-keep-who"><span kf-keep-who-pics="" me="me" keepers="keep.keepers" keep="keep"></span> <span class="kf-keep-who-others" ng-if="showOthers()"></span> <span kf-keep-who-text="" keep="keep"></span></div><div class="kf-keep-arrow-1"><div class="kf-keep-arrow-2"><div class="kf-keep-arrow-3"><div class="kf-keep-arrow-4"></div></div></div></div><div class="kf-keep-hover-button-wrapper"><div class="kf-keep-hover-button-table"><div class="kf-keep-hover-button-cell"><div class="kf-keep-hover-button"><span class="kf-keep-button-preview">Preview</span> <span class="kf-keep-button-close">Close</span></div></div></div></div><div class="kf-drag-mask"></div></li>');
}]);

angular.module('keeps/keeps.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('keeps/keeps.tpl.html',
    '<div class="kf-main-keeps keeps-wrapper" antiscroll="{ autoHide: false }"><div smart-scroll="" scroll-distance="scrollDistance" scroll-disabled="isScrollDisabled()" scroll-next="scrollNext()" class="keeps-list"><ol class="kf-my-keeps"><div ng-repeat="keep in keeps"><div kf-keep="" is-previewed="isPreviewed(keep)" is-selected="!!isSelected(keep)" keep="keep" me="me" toggle-select="toggleSelect(keep)" click-action="onClickKeep(keep, event)" drag-keeps="dragKeeps(keep, event, mouseX, mouseY)" stop-dragging-keeps="stopDraggingKeeps()"></div></div></ol><ol class="kf-shadow-dragged-keeps"><span class="kf-shadow-keep-first"><li ng-if="data.draggedKeeps.length >= 1" class="kf-dragged-keep" kf-keep="" kf-shadow-keep-first="" keep="data.draggedKeeps[0]" is-previewed="isPreviewed(keep)" is-selected="!!isSelected(keep)"></li><li></li></span> <span class="kf-shadow-keep-second"><li ng-if="data.draggedKeeps.length === 3" class="kf-dragged-keep" kf-keep="" kf-shadow-keep-first="" keep="data.draggedKeeps[1]" is-previewed="isPreviewed(keep)" is-selected="!!isSelected(keep)"></li><li></li></span> <span class="kf-shadow-keep-ellipsis" ng-class="{\'active\': data.draggedKeeps.length >= 4}"><div class="kf-shadow-keep-ellipsis-line"></div><div class="kf-shadow-keep-ellipsis-counter">{{data.draggedKeeps.length - 2}} other keeps selected</div></span><div class="kf-shadow-keep-ellipsis-counter-hidden">{{data.draggedKeeps.length - 2}} other keeps selected</div><span class="kf-shadow-keep-last"><li ng-if="data.draggedKeeps.length >= 2" class="kf-dragged-keep" kf-keep="" kf-shadow-keep-last="" keep="data.draggedKeeps[data.draggedKeeps.length-1]" is-previewed="isPreviewed(keep)" is-selected="!!isSelected(keep)"></li></span></ol><div class="kf-keep-group-title-fixed"></div><img class="kf-keeps-loading" ng-show="keepsLoading" src="/img/wait.gif"> <a class="kf-keeps-load-more" ng-show="isShowMore()" href="javascript:" ng-click="scrollNext()">Show more</a></div></div>');
}]);

angular.module('layout/footer/footer.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/footer/footer.tpl.html',
    '<div class="kf-footer" antiscroll="{ autoHide: false }"><h2 class="kf-footer-header">Getting Started with Kifi</h2><ul class="kf-footer-list about-kifi"><li><a class="kf-footer-item-link kifi-tutorial" href="#" ng-click="data.showGettingStarted = true"><span class="kf-footer-item-icon kf-footer-item-icon-tutorial"></span> Quick guide to get started</a><div ng-if="data.showGettingStarted"><iframe class="kifi-onboarding-iframe" ng-src="https://www.kifi.com/assets/onboarding.html" frameborder="0"></iframe></div></li><li class="install-kifi"><a class="kf-footer-item-link" href="javascript:" ng-click="triggerInstall()"><span class="kf-footer-item-icon kf-footer-item-icon-install"></span> <span ng-show="!installInProgress()">Install the extension</span> <span ng-show="installInProgress()">Installing...</span> <span ng-show="installed()">Installed!</span></a></li><li class="import-bookmarks"><a class="kf-footer-item-link" href="javascript:" ng-click="importBookmarks()"><span class="kf-footer-item-icon kf-footer-item-icon-install"></span> Import your bookmarks</a></li><li><a class="kf-footer-item-link" href="/invite"><span class="kf-footer-item-icon kf-footer-item-icon-friends"></span> Find friends</a></li></ul><div kf-modal="" show="data.showInstallErrorModal"><div kf-basic-modal-content=""><div>Something seems to have gone wrong installing the browser extension.</div><div>Please try again later, or <a href="http://support.kifi.com/customer/portal/emails/new">contact us</a>.</div></div></div><h2 class="kf-footer-header">Kifi Support and Updates</h2><ul class="kf-footer-list about-us"><li><a class="kf-footer-item-link support-center" href="http://support.kifi.com"><span class="kf-footer-item-icon kf-footer-item-icon-support"></span> Support center</a></li><li><a class="kf-footer-item-link contact-us" href="http://support.kifi.com/customer/portal/emails/new"><span class="kf-footer-item-icon kf-footer-item-icon-contact"></span> Contact us</a></li><li><a class="kf-footer-item-link updates-features" href="http://blog.kifi.com"><span class="kf-footer-item-icon kf-footer-item-icon-blog"></span> Kifi blog</a></li></ul><div class="kf-footer-more"><a href="https://www.kifi.com/privacy" target="_self">Privacy</a> <span class="kf-footer-sep">|</span> <a href="https://www.kifi.com/terms" target="_self">Terms</a> <span class="kf-footer-sep">|</span> <a href="https://www.kifi.com/about" target="_self">About Kifi</a> <span class="kf-footer-sep">|</span> <a href="http://www.42go.com">About FortyTwo</a></div></div>');
}]);

angular.module('layout/leftCol/leftCol.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/leftCol/leftCol.tpl.html',
    '<div class="kf-col-inner kf-col-inner-left" ng-controller="LeftColCtrl"><div class="kf-header-left"><a class="kf-header-logo" href="/"></a> <span class="kf-header-callout-wrap"><a class="kf-header-callout" href="http://www.42go.com/join_us.html">we\'re hiring!</a></span></div><div kf-profile-card=""></div><div kf-nav=""></div><div kf-tags=""></div></div>');
}]);

angular.module('layout/main/main.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/main/main.tpl.html',
    '<div class="kf-col-inner" ng-controller="MainCtrl"><div class="kf-query-wrap" ng-class="{ empty: isEmpty(), focus: focus }"><input kf-no-drag-input="" class="kf-query" type="text" placeholder="Find anything..." ng-model="search.text" ng-keydown="onKeydown($event)" ng-focus="onFocus()" ng-blur="onBlur()" ng-change="onChange()"><span class="kf-query-icon"><b class="kf-query-mag"></b> <a class="kf-query-x" href="javascript:" ng-click="clear()"></a></span><div class="kf-small-tooltip" ng-if="undo.isSet() || tooltipMessage"><span class="kf-small-tooltip-box"><span class="kf-small-tooltip-message">{{undo.message || tooltipMessage}}</span> <a class="kf-small-tooltip-link" href="javascript:" ng-click="undo.undo()" ng-if="undo.isSet()">Undo</a> <span></span></span></div></div><div ng-view=""></div><div ng-if="modal"><div ng-include="\'keep/importModal.tpl.html\'"></div><div ng-include="\'social/addNetworksModal.tpl.html\'"></div><div kf-add-keeps-modal=""></div></div></div>');
}]);

angular.module('layout/nav/nav.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/nav/nav.tpl.html',
    '<div class="kf-nav-group-name">You</div><ul class="kf-nav-group"><li class="kf-nav-item kf-nav-keeps" ng-class="{ active: isActive(\'/\') }"><a class="kf-nav-link" href="/"><span class="kf-nav-name">Your Keeps</span> <span class="kf-nav-count" ng-class="{ empty: !counts.keepCount }">{{counts.keepCount}}</span></a></li><li class="kf-nav-item kf-nav-friends" ng-class="{ active: isActive(\'/friends\') }"><a class="kf-nav-link" href="/friends"><span class="kf-nav-badge" ng-class="{ empty: !counts.friendsNotifCount }">{{counts.friendsNotifCount}}</span> <span class="kf-nav-name">Your Kifi Friends</span> <span class="kf-nav-count" ng-class="{ empty: !counts.friendsCount }">{{counts.friendsCount}}</span></a></li><li class="kf-nav-item kf-nav-invite" ng-class="{ active: isActive(\'/invite\') }"><a class="kf-nav-link-btn" href="/invite"><span class="kf-nav-name">Find Friends</span></a></li></ul>');
}]);

angular.module('layout/rightCol/rightCol.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/rightCol/rightCol.tpl.html',
    '<div class="kf-col-inner" ng-controller="RightColCtrl"><nav class="kf-top-right-nav"><a href="javascript:" ng-click="logout()">Log out</a></nav><div ng-include="\'layout/footer/footer.tpl.html\'"></div><div kf-detail=""></div></div>');
}]);

angular.module('profile/emailImport.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('profile/emailImport.tpl.html',
    '<div><h3>Import your email contacts to Kifi</h3><h5>Message your email contact directly from Kifi</h5><a class="profile-nw nw-email import-gmail" href="javascript:" ng-click="importGmailContacts()" ng-bind="addressBookImportText"></a><div ng-if="addressBooks"><div class="profile-email-accounts-title"><span>Your imported accounts:</span> </div><table class="profile-email-accounts"><tbody><tr ng-repeat="addressBook in addressBooks" class="profile-email-account {{addressBook.state}}" data-id="{{addressBook.id}}"><td class="profile-email-status"></td><td class="profile-email-address">{{addressBook.ownerEmail}}</td><td class="profile-email-separator">|</td><td class="profile-email-contact-count">{{addressBook.numProcessed}} out of {{addressBook.numContacts}} contacts imported</td></tr></tbody></table></div></div>');
}]);

angular.module('profile/profile.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('profile/profile.tpl.html',
    '<div ng-controller="ProfileCtrl"><div class="profile-wrapper" antiscroll="{ autoHide: false }"><div class="profile"><h2>Settings</h2><div class="profile-container"><div kf-profile-image="" pic-url="me.picUrl"></div><div class="profile-input-wrapper"><div class="profile-input-title">Name</div><div class="profile-input-box profile-input-box-name"><span class="profile-input-placeholder"><span class="profile-placeholder-first-name">{{me.firstName}}</span> <span class="profile-placeholder-last-name">{{me.lastName}}</span></span><input class="profile-input-input profile-first-name" disabled type="text" name="firstName" placeholder="First name" value="{{me.firstName}}" novalidate><input class="profile-input-input profile-last-name" disabled type="text" name="lastName" placeholder="Last name" value="{{me.lastName}}" novalidate></div></div><div class="profile-input-wrapper"><div class="profile-input-title">Description tagline <span class="profile-input-remaining-chars"></span></div><div kf-profile-input="" input-state="descInput" input-save-action="saveDescription(value)" explicit-enabling="true"><input class="profile-input-input profile-description-input" ng-disabled="!descInput.editing" kf-focus-if="" focus-cond="descInput.editing" ng-model="descInput.value" type="text" name="description" placeholder="Enter your description tagline" maxlength="50"></div></div><div class="profile-input-wrapper profile-email"><div class="profile-input-title">Contact email</div><div kf-profile-input="" input-state="emailInput" input-validate-action="validateEmail(value)" input-save-action="saveEmail(value)" explicit-enabling="true"><input class="profile-input-input profile-email-input" ng-disabled="!emailInput.editing" kf-focus-if="" focus-cond="emailInput.editing" ng-model="emailInput.value" type="text" name="email" placeholder="Enter your email address" novalidate></div><div kf-modal="" show="showEmailChangeDialog.value"><div kf-basic-modal-content="" with-cancel="" action-text="Continue" action="confirmSaveEmail()" cancel="cancelSaveEmail()"><p>You are about to change your primary email to <span class="email-address">{{me.primaryEmail.address}}</span>.</p><p>We will send a verification email to this email address to prove that you own it. To verify, please click on a verification link on the email.</p></div></div></div><div class="profile-email-address-unverified" ng-if="isUnverified({value: me.primaryEmail})">Your email is pending your verification (<a class="profile-email-pending-resend" href="javascript:" ng-click="resendVerificationEmail()">Resend verification</a>).</div><div class="profile-email-address-pending" ng-if="me.primaryEmail.isPendingPrimary">Your new primary email, <span class="profile-email-address-pending-email">{{me.primaryEmail.address}}</span>, is pending your verification (<a class="profile-email-pending-resend" href="javascript:" ng-click="resendVerificationEmail()">Resend verification</a>, <a class="profile-email-pending-cancel" href="javascript:" ng-click="cancelPendingPrimary()">Cancel</a>).</div><div kf-profile-email-addresses="" input-state="addEmailInput" email-list="me.emails" validate-email-action="validateEmail(value)" add-email-action="addEmail(value)" resend-verification-email-action="resendVerificationEmail(value)"></div><div kf-modal="" show="showResendVerificationEmailDialog.value"><div kf-basic-modal-content="" title="{{emailForVerificationModalTitle}}"><p>You will shortly receive a verification email from us at the following address: <span class="email-address">{{emailForVerification}}</span>.</p><p>To verify, please click on a verification link on the email.</p></div></div><div kf-profile-change-password=""></div></div><div class="profile-section"><h3>Connect your social networks to Kifi</h3><h5>Find which of your friends are using Kifi</h5><ul class="profile-networks"><li kf-facebook-connect-button="" data-network="facebook" ng-class="{ \'connected\': isFacebookConnected }"><a class="profile-nw nw-facebook" href="https://www.kifi.com/link/facebook" target="_self" ng-click="connectFacebook()" ng-show="!isFacebookConnected">Facebook</a> <a class="profile-nw nw-facebook" ng-href="{{fbProfileUrl}}" target="_blank" ng-show="isFacebookConnected">Facebook</a> <span class="profile-nw-connected"><span class="profile-placeholder">&nbsp;</span> <span class="profile-connected">Connected</span> <a class="profile-disconnect" href="javascript:" ng-click="disconnectFacebook()">Disconnect?</a></span></li><li kf-linkedin-connect-button="" data-network="linkedin" ng-class="{ \'connected\': isLinkedInConnected }"><a class="profile-nw nw-linkedin" href="https://www.kifi.com/link/linkedin" target="_self" ng-click="connectFacebook()" ng-show="!isLinkedInConnected">LinkedIn</a> <a class="profile-nw nw-linkedin" ng-href="{{liProfileUrl}}" target="_blank" ng-show="isLinkedInConnected">LinkedIn</a> <span class="profile-nw-connected"><span class="profile-placeholder">&nbsp;</span> <span class="profile-connected">Connected</span> <a class="profile-disconnect" href="javascript:" ng-click="disconnectLinkedIn()">Disconnect?</a></span></li></ul></div><hr class="profile-section-divider"><div class="profile-section" kf-email-import=""></div></div></div></div>');
}]);

angular.module('profile/profileChangePassword.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('profile/profileChangePassword.tpl.html',
    '<div class="profile-change-password"><a class="profile-change-password-title-wrapper" href="javascript:" ng-click="toggle()"><span class="profile-setting-arrow-right" ng-show="!isOpen"></span> <span class="profile-setting-arrow-down" ng-show="isOpen"></span> <span class="profile-change-password-title">Change your password</span></a><div class="profile-change-password-change" ng-show="isOpen"><div class="profile-input-wrapper profile-old-password"><div class="profile-input-title">Enter your current password</div><div class="profile-input-box"><input class="profile-input-input" ng-model="inputs.oldPassword" type="password" name="old-password" placeholder="Existing Password" value="" novalidate></div></div><div class="profile-input-wrapper profile-new-password"><div class="profile-input-title">New Password</div><div class="profile-input-box"><input class="profile-input-input" ng-model="inputs.newPassword1" type="password" name="new-password1" placeholder="Choose a new password" value="" novalidate></div><div class="profile-input-box"><input class="profile-input-input" ng-model="inputs.newPassword2" type="password" name="new-password2" placeholder="And enter it again" value="" novalidate></div></div><div class="profile-change-password-error">{{errorMessage}}</div><div><a class="profile-change-password-save" href="javascript:" ng-click="updatePassword()">Update password</a></div></div><div class="profile-change-password-success">{{successMessage}}</div></div>');
}]);

angular.module('profile/profileEmailAddresses.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('profile/profileEmailAddresses.tpl.html',
    '<div class="profile-email-addresses"><a class="profile-email-addresses-title-wrapper" href="javascript:" ng-click="toggle()"><span class="profile-setting-arrow-right" ng-show="!isOpen"></span> <span class="profile-setting-arrow-down" ng-show="isOpen"></span> <span class="profile-email-addresses-title">Manage your email addresses</span></a><div class="profile-email-address-manage" ng-show="isOpen"><div class="profile-email-address-manage-description" ng-if="emailList.length > 0">These are the email addresses associated with your Kifi account. You may log in with them and Kifi members can find you using these email addresses via search.</div><ul class="profile-email-address-list"><li ng-repeat="email in emailList" class="profile-email-address-item"><span class="profile-email-address-item-email" ng-class="{\'profile-email-address-item-email-important\': email.isPrimary || email.isPendingPrimary || email.isPlaceholderPrimary}">{{email.address}}</span> <span class="profile-email-address-item-primary" ng-if="email.isPrimary || email.isPlaceholderPrimary">(primary)</span> <span class="profile-email-address-item-pending-primary" ng-if="email.isPendingPrimary && !email.isPrimary && !email.isPlaceholderPrimary">(pending primary)</span> <span class="profile-email-address-item-unverified" ng-if="!email.isVerified">(<a class="profile-email-pending-resend" href="javascript:" ng-click="resendVerificationEmail(email.address)">unverified</a>)</span><div class="profile-email-address-item-arrow" href="javascript:" ng-if="!(email.isPrimary || email.isPendingPrimary || email.isPlaceholderPrimary)" ng-click="openDropdownForEmail($event, email.address)"><ul class="profile-email-address-item-dropdown" ng-show="emailWithActiveDropdown === email.address"><li><a class="profile-email-address-item-make-primary" href="javascript:" ng-if="email.isVerified" ng-click="makePrimary(email.address)">Make primary</a></li><li><a class="profile-email-address-item-delete" href="javascript:" ng-click="deleteEmail(email.address)">Delete</a></li></ul></div></li></ul><a class="profile-email-address-add" href="javascript:" ng-click="enableAddEmail()" ng-show="!(state.editing || state.invalid)">+ add a new email address</a><div class="profile-email-address-add-box" ng-show="state.editing || state.invalid"><div kf-profile-input="" input-state="state" input-validate-action="validateEmail(value)" input-save-action="addEmail(value)" action-label="Add"><input class="profile-email-address-add-input" kf-focus-if="" focus-cond="state.editing" ng-model="state.value" type="text" name="new_email" placeholder="Type a new email address"></div></div><div kf-modal="" show="showEmailDeleteDialog.value"><div kf-basic-modal-content="" with-cancel="" with-warning="" title="Delete {{emailToBeDeleted}}?" action-text="Delete" action="confirmDeleteEmail()"><p>Are you sure you want to delete this email address?</p><ul><li>You will not be able to login with this email address</li><li>Your friends will not be able to find you by this email address</li></ul></div></div></div></div>');
}]);

angular.module('profile/profileImage.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('profile/profileImage.tpl.html',
    '<div class="profile-image-wrapper"><div class="profile-image" ng-click="selectFile()" ng-style="{\'background-image\': \'url(\' + picUrl + \')\'}"></div><label class="profile-image-label"><a class="profile-image-change" href="javascript:" ng-click="selectFile()">Change photo</a><input class="profile-image-file" type="file" name="photo" accept="image/*" onchange="angular.element(this).scope().fileChosen(this.files)"></label><div kf-modal="" show="showImageEditDialog.value"><div kf-basic-modal-content="" with-cancel="" title="Position and size of your photo" action-text="Submit" action="uploadImage()" cancel="resetChooseImage()"><div class="kf-profile-image-dialog-image"><div class="kf-profile-image-dialog-mask"></div></div><div class="kf-profile-image-dialog-slider" ui-slider="zoomSlider" ng-model="zoomSlider.value"></div></div></div><div kf-modal="" no-user-hide="" show="showImageUploadingModal.value"><div class="dialog-body-wrap"><div class="dialog-header">Uploading image...</div></div></div><div kf-modal="" show="showImageUploadFailedDialog.value"><div kf-basic-modal-content="" with-warning=""><div class="dialog-header dialog-warning">Sorry, we could not upload your image. Please try again later.</div></div></div></div>');
}]);

angular.module('profile/profileInput.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('profile/profileInput.tpl.html',
    '<div class="profile-input-box"><div ng-class="{ error: state.invalid }"><div ng-transclude=""></div><div class="input-error" ng-show="state.invalid"><div class="error-header" ng-bind-html="errorHeader"></div><div class="error-body" ng-bind-html="errorBody"></div></div></div><a class="profile-input-edit" href="javascript:" ng-hide="state.editing" ng-click="edit()" ng-if="explicitEnabling">Edit</a> <a class="profile-input-save" href="javascript:" ng-show="!explicitEnabling || state.editing" ng-click="save()">{{actionLabel || \'Save\'}}</a></div>');
}]);

angular.module('profileCard/profileCard.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('profileCard/profileCard.tpl.html',
    '<div class="kf-my-identity"><a class="kf-my-settings-wrapper" href="/profile"><span class="kf-my-pic" ng-style="{\'background-image\': \'url(\' + me.picUrl + \')\'}"></span> <span class="kf-my-settings-shade"></span> <span class="kf-my-settings"><span class="kf-my-settings-icon"></span> <span class="kf-my-settings-text">Settings</span></span></a><div class="kf-my-name">{{me.firstName}} {{me.lastName}}</div><div class="kf-my-description" ng-if="me.description">{{me.description}}</div><div class="kf-help-rank" ng-if="me.totalKeepsClicked > 0" ng-click="openHelpRankHelp()"><hr ng-if="me.description"><div><span class="kf-heart">♥</span> Your Keeps helped friends {{me.totalKeepsClicked}} times</div></div><div kf-modal="" ng-if="data.showHelpRankHelp" show="data.showHelpRankHelp"><div kf-basic-modal-content="" centered="true" single-action="false"><div class="kf-help-rank-help"><div class="kf-block-top">The vision of Kifi is about people helping people.<br>Here\'s where you come in:</div><div class="kf-block"><span class="kf-emph">{{me.uniqueKeepsClicked}}</span> of your public keeps have been clicked on <span class="kf-emph">{{me.totalKeepsClicked}}</span> times by your friends<br>when they searched on Google or Kifi.com</div><div class="kf-block-bottom">Do you like this feedback?<div class="dialog-buttons"><button ng-click="yesLikeHelpRank()">Yes</button> <button ng-click="noLikeHelpRank()">No</button></div></div></div></div></div></div>');
}]);

angular.module('search/search.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('search/search.tpl.html',
    '<div class="kf-main-search"><div class="kf-main-head"><div class="kf-subtitle"><span class="kf-check-all" ng-class="{ enabled: isCheckEnabled(), checked: isSelectedAll(), \'multi-checked\': isMultiChecked() }" ng-click="toggleSelectAll()" ng-mouseover="onMouseoverCheckAll()" ng-mouseout="onMouseoutCheckAll()"></span> <span class="kf-subtitle-text">{{getSubtitle()}}</span> <span class="kf-search-filters"><a class="kf-search-filter" ng-class="{ selected: isFilterSelected(\'m\'), enabled: isEnabled(\'m\') }" ng-href="{{getFilterUrl(\'m\')}}">Your keeps ({{results.myTotal}})</a> <a class="kf-search-filter" ng-class="{ selected: isFilterSelected(\'f\'), enabled: isEnabled(\'f\') }" ng-href="{{getFilterUrl(\'f\')}}">Friends keeps ({{results.friendsTotal}})</a> <a class="kf-search-filter" ng-class="{ selected: isFilterSelected(\'a\'), enabled: isEnabled(\'a\') }" ng-href="{{getFilterUrl(\'a\')}}">All keeps</a></span></div></div><div kf-keeps="" keeps="keeps" keeps-loading="loading" keeps-has-more="hasMore()" scroll-distance="scrollDistance" scroll-disabled="scrollDisabled" scroll-next="getNextKeeps()" keep-click="analyticsTrack"></div></div>');
}]);

angular.module('social/addNetworksModal.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('social/addNetworksModal.tpl.html',
    '<div kf-modal="" ng-if="data.showAddNetworks" show="data.showAddNetworks" kf-width="750px"><div kf-basic-modal-content="" centered="true" action-text="Done!"><div kf-social-connect-networks=""></div></div></div>');
}]);

angular.module('social/connectNetworks.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('social/connectNetworks.tpl.html',
    '<div class="social-connect" ng-show="data.show"><div class="social-connect-title">Connect your social networks to Kifi</div><p class="social-connect-desc">Connect your networks to quickly find people you may know already using Kifi.</p><div class="social-connect-buttons"><div class="social-connect-row"><div ng-if="facebook.profileUrl"><div class="social-connected-button facebook">Facebook Connected</div><span class="social-notice-badge" ng-if="facebookStatus() === \'good\'"><span class="notice-button good"></span></span> <span class="social-notice-badge" ng-if="facebookStatus() === \'refreshing\'"><span class="notice-message import"><div class="bouncy-ball"><div class="pace pace-active"><div class="pace-progress"></div><div class="pace-activity"></div></div></div>Importing your contacts</span></span> <span class="social-notice-badge" ng-if="facebookStatus() === \'expired\'" ng-click="connectFacebook()"><span class="notice-button warn">!</span> <span class="notice-message refresh">Refresh connection</span></span></div><div ng-if="!facebook.profileUrl"><div class="social-connect-button facebook" ng-click="connectFacebook()">Connect Facebook</div></div></div><div class="social-connect-row"><div ng-if="linkedin.profileUrl"><div class="social-connected-button linkedin">LinkedIn Connected</div><span class="social-notice-badge" ng-if="linkedinStatus() === \'good\'"><span class="notice-button good"></span></span> <span class="social-notice-badge" ng-if="linkedinStatus() === \'refreshing\'"><span class="notice-message import"><div class="bouncy-ball"><div class="pace pace-active"><div class="pace-progress"></div><div class="pace-activity"></div></div></div>Importing your contacts</span></span> <span class="social-notice-badge" ng-if="linkedinStatus() === \'expired\'" ng-click="connectLinkedIn()"><span class="notice-button warn">!</span> <span class="notice-message refresh">Refresh connection</span></span></div><div ng-if="!linkedin.profileUrl"><div class="social-connect-button linkedin" ng-click="connectLinkedIn()">Connect LinkedIn</div></div></div><div class="social-connect-row" ng-if="gmail.length === 0"><div class="social-connect-button gmail" ng-click="importGmail()">Connect Gmail</div></div><div ng-repeat="account in gmail" ng-if="gmail.length > 0"><div class="social-connect-row social-extended"><div class="social-connected-button gmail">Gmail Connected<div class="social-account-name">{{account.ownerEmail}}</div></div><span class="social-notice-badge"><span class="notice-button good"></span></span></div></div><div class="social-more-accounts" ng-if="gmail.length > 0" ng-click="importGmail()">Add more Gmail accounts</div></div></div>');
}]);

angular.module('social/networksNeedAttention.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('social/networksNeedAttention.tpl.html',
    '<div class="kf-networks-need-attention" ng-if="networksNeedAttention()"><div kf-alert-banner="" action-text="Fix it" action="doShow">One or more of your connected social networks needs your attention</div></div>');
}]);

angular.module('tagKeeps/tagKeeps.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('tagKeeps/tagKeeps.tpl.html',
    '<div class="kf-main-tag-keeps"><div class="kf-main-head"><h1 class="kf-main-title">Tags / {{tag.name}}</h1><div class="kf-subtitle"><span class="kf-check-all" ng-class="{ enabled: isCheckEnabled(), checked: isSelectedAll(), \'multi-checked\': isMultiChecked() }" ng-click="toggleSelectAll()" ng-mouseover="onMouseoverCheckAll()" ng-mouseout="onMouseoutCheckAll()"></span> <span class="kf-subtitle-text">{{getSubtitle()}}</span></div></div><div kf-keeps="" keeps="keeps" keeps-loading="loading" keeps-has-more="hasMore()" scroll-distance="scrollDistance" scroll-disabled="scrollDisabled" scroll-next="getNextKeeps()"></div></div>');
}]);

angular.module('tags/tagItem.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('tags/tagItem.tpl.html',
    '<li class="kf-nav-item kf-tag clickable" ng-mouseenter="enableHover()" ng-mouseleave="disableHover()"><div class="kf-nav-link" ng-class="{ renaming: isRenaming, \'kf-dragged\': isDragging, \'kf-drag-target\': isDragTarget, hover: isHovering }" ng-click="navigateToTag($event)" ui-draggable="true" drag="tag" drag-channel="tagDragChannel" ui-on-drop="onKeepDrop($data)" drop-channel="keepDragChannel" drag-enter-class="kf-candidate-drag-target"><div class="kf-tag-drag-container" ui-on-drop="onTagDrop($data)" drop-channel="tagDragChannel" drag-enter-class="kf-candidate-tag-drag-target"><div class="kf-tag-info"><span class="kf-tag-icon"></span> <span ng-show="!isRenaming"><span class="kf-tag-name">{{tag.name}}</span> <span class="kf-tag-count" ng-show="!isWaiting">{{tag.keeps}}</span><div class="kf-tag-waiting" ng-show="isWaiting"></div></span></div><div class="kf-tag-tools" ng-show="!isRenaming" ng-class="{ open: isDropdownOpen }" ng-click="$event.stopPropagation()"><div class="kf-tag-dropdown-toggle" ng-show="!isDragging" ng-click="toggleDropdown()"><div class="kf-tag-tool-icon"></div></div><div class="kf-tag-handle"><div class="kf-tag-tool-icon"></div></div><ul class="kf-dropdown-menu" ng-show="isDropdownOpen"><li><a href="javascript:" ng-click="setRenaming()">Rename</a></li><li><a href="javascript:" ng-click="remove()">Remove</a></li></ul></div><input class="kf-tag-rename" type="text" placeholder="Type new tag name" ng-model="renameTag.value" ng-keydown="onRenameKeydown($event)" ng-show="isRenaming"><div class="kf-drag-mask"></div><div class="kf-tag-drag-mask"></div><div class="kf-tag-new-location-mask hidden"></div></div></div></li>');
}]);

angular.module('tags/tags.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('tags/tags.tpl.html',
    '<div class="kf-tags"><div class="kf-nav-group-name">Tags</div><label class="kf-nav-item kf-tag-input-box"><input kf-no-drag-input="" name="filterName" placeholder="Find or add a tag..." ng-model="filter.name" focus-when="focusFilter" ng-keydown="onKeydown($event)" ng-blur="dehighlight()"><a class="kf-tag-input-clear" href="javascript:" ng-click="clearFilter(true)" ng-show="filter.name">&times;</a></label><div class="kf-tag-list-hidden"></div><ul class="kf-tag-list" antiscroll="{ autoHide: false }"><li kf-tag-item="" tag="tag" take-focus="unfocus()" release-focus="refocus()" has-new-location="newLocationTagId === tag.id" watch-tag-reorder="watchTagReorder()" reorder-tag="reorderTag(isTop,srcTag,dstTag)" received-tag-drag="receivedTagDrag()" view-tag="viewTag(tagId)" remove-tag="removeTag(tag)" ng-class="{ active: isActiveTag(tag), highlight: isHighlight(tag), new: tag.isNew }" ng-repeat="tag in shownTags = (tags | filter: filter)"></li><li class="kf-nav-item kf-tag kf-tag-new" ng-class="{ highlight: isHighlightNew() }" ng-show="showAddTag()"><a href="javascript:" ng-click="create(filter.name)"><span class="kf-tag-icon kf-tag-icon-create"></span> <span class="kf-tag-caption">Add a new tag:</span> <span class="kf-tag-name">{{filter.name}}</span></a></li></ul></div>');
}]);
