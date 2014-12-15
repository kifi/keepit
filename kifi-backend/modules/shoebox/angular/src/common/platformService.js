'use strict';

angular.module('kifi')

.factory('platformService', [
  '$window', '$q', 'env', '$timeout',
  function ($window, $q, env, $timeout) {


    var isAndroid = function () {
      return (/Android/).test(navigator.userAgent);
    };

    var isIPhone = function () {
      return (/iPhone|iPod/).test(navigator.userAgent);
    };

    var isBot = function () {
      return (/bot|googlebot|crawler|spider|robot|crawling/i).test($window.navigator.userAgent);
    };

    var goToAppOrStore = function (url) {
      var safeUrl;
      if (isSupportedMobilePlatform()) {
        if (isIPhone()) {
          safeUrl = 'kifi:' + (url || '//kifi.com').replace(/https?:/, '');
          var branchIdx = (url || '').indexOf('branch');
          if (branchIdx !== -1 && branchIdx > url.indexOf('?')) { // url ends with 'branch'
            createBranchLink({
              url: safeUrl
            }).then(function (url) {
              $window.location = url;
            });
          } else {
            $timeout(function () {
              $window.location = safeUrl;
            }, 200);
            $timeout(function () {
              $window.location = 'itms://itunes.apple.com/us/app/kifi/id740232575';
            }, 225);
          }
        } else if (isAndroid()) {
          url = url || 'kifi.com';
          safeUrl = url.replace(/https?:\/\/((www.)?kifi.com)?\/?/, '');
          $window.location = 'intent://' + safeUrl + '#Intent;package=com.kifi;scheme=kifi;action=com.kifi.intent.action.APP_EVENT;end;';
        }
      }
    };


    var Branch;

    var branchInit = function () {
      if (Branch) {
        return $q.when(Branch);
      }
      var deferred = $q.defer();

      // Because jshint complains `isDev` is never used because Branch_Init is ignored.
      var isDev = env.dev; // jshint ignore:line

      /* jshint ignore:start */
      // https://github.com/BranchMetrics/Web-SDK#quick-install
      var config = {
        app_id: '58363010934112339',
        debug: env.dev,
        init_callback: function () {
          Branch = window.branch;
          deferred.resolve(window.branch);
        }
      };
      var Branch_Init=function(a){self=this,self.app_id=a.app_id,self.debug=a.debug,self.init_callback=a.init_callback,self
      .queued=[],this.init=function(){for(var a=["close","logout","track","identify","createLink","showReferrals","showCredits",
      "redeemCredits","appBanner"],b=0;b<a.length;b++)self[a[b]]=function(a){return function(){self.queued.push([a].concat(Array
      .prototype.slice.call(arguments,0)))}}(a[b])},self.init();var b=document.createElement("script");b.type="text/javascript",
      b.async=!0,b.src="https://bnc.lt/_r",document.getElementsByTagName("head")[0].appendChild(b),self._r=function(){if(
      void 0!==window.browser_fingerprint_id){var a=document.createElement("script");a.type="text/javascript",a.async=!0,a
      .src="https://s3-us-west-1.amazonaws.com/branch-sdk/branch.min.js",document.getElementsByTagName("head")[0].appendChild(a)
      }else window.setTimeout("self._r()",100)},self._r()};window.branch=new Branch_Init(config);
      /* jshint ignore:end */

      return deferred.promise;
    };

    var createBranchLink = function (data) {
      var deferred = $q.defer();
      branchInit().then(function (branch) {
        branch.createLink({
          data: data
        }, function (url){
          deferred.resolve(url);
        });
      });
      return deferred.promise;
    };

    var isSupportedMobilePlatform = function () {
      return isIPhone() || isAndroid();
    };

    var api = {
      isSupportedMobilePlatform: isSupportedMobilePlatform,
      goToAppOrStore: goToAppOrStore,
      isBot: isBot
    };

    return api;
  }
]);
