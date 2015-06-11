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
      // https://github.com/BranchMetrics/Web-SDK#quick-install-web-sdk
      (function(b,r,a,n,c,h,_,s,d,k){if(!b[n]||!b[n]._q){for(;s<_.length;)c(h,_[s++]);d=r.createElement(a);d.async=1;d.src="https://cdn.branch.io/branch-v1.5.4.min.js";k=r.getElementsByTagName(a)[0];k.parentNode.insertBefore(d,k);b[n]=h}})(window,document,"script","branch",function(b,r){b[r]=function(){b._q.push([r,arguments])}},{_q:[],_v:1},"init data setIdentity logout track link sendSMS referrals credits redeem banner closeBanner".split(" "),0); // jshint ignore:line
      window.branch.setDebug(isDev);
      window.branch.init('58363010934112339', function(err, data) {
        if (data) {
          Branch = window.branch;
          deferred.resolve(window.branch);
        }
      });
      /* jshint ignore:end */

      return deferred.promise;
    };

    var createBranchLink = function (data) {
      var deferred = $q.defer();
      branchInit().then(function (branch) {
        branch.link({
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
      isIPhone: isIPhone,
      isAndroid: isAndroid,
      goToAppOrStore: goToAppOrStore,
      isBot: isBot
    };

    return api;
  }
]);
