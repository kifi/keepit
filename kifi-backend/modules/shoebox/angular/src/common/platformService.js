'use strict';

angular.module('kifi')

.factory('platformService', [
  '$window',
  function ($window) {

    var timer,
      heartbeat,
      lastInterval,
      isRunning = false;

    var isAndroid = function () {
      return (/Android/).test(navigator.userAgent);
    };

    var isIPhone = function () {
      return (/iPhone|iPod/).test(navigator.userAgent);
    };

    function abortNavigation() {
        clearTimeout(timer);
        clearTimeout(heartbeat);
        isRunning = false;
    }

    $window.addEventListener('pageshow', abortNavigation, false);
    $window.addEventListener('pagehide', abortNavigation, false);

    // for browsers except Safari (which do not support pageshow and pagehide properly)
    function intervalHeartbeat() {
        var now = +new Date(), diff = now - lastInterval - 200;
        lastInterval = now;
        if (diff > 1000) {
          abortNavigation();
        }
    }

    var goToAppOrStore = function (url) {
      var safeUrl = url.replace(/https?:/, '');
      if (!isRunning) {
        isRunning = true;
        lastInterval = +new Date();
        heartbeat = setInterval(intervalHeartbeat, 200);
        $window.location = 'kifi:' + safeUrl;
        timer = setTimeout(goToMobileStore, 2000);
      }
    };

    var goToMobileStore = function () {
      if (isAndroid()) {
        $window.location = 'market://details?id=com.google.earth';
      } else if (isIPhone()) {
        $window.location = 'itms://itunes.apple.com/us/app/kifi/id740232575';
      }
    };

    var isSupportedMobilePlatform = function () {
      return isIPhone() || isAndroid();
    };

    var api = {
      isSupportedMobilePlatform: isSupportedMobilePlatform,
      goToAppOrStore: goToAppOrStore
    };

    return api;
  }
]);
