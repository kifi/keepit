'use strict';

angular.module('kifi.installService', [])

.factory('installService', ['$window',
  function ($window) {
    var isChrome = $window.chrome && $window.chrome.webstore && $window.chrome.webstore.install;
    var isFirefox = !isChrome && ('MozBoxSizing' in $window.document.documentElement.style) || ~$window.navigator.userAgent.indexOf('Firefox');
    var majorVersion = +($window.navigator.userAgent.match(/(?:Chrome|Firefox)\/(\d+)/) || [,999])[1];
    var supported = isChrome && majorVersion >= 26 || isFirefox && majorVersion >= 20;

    var api = {
      triggerInstall: function () {
        if (isChrome && supported) {
          $window.chrome.webstore.install(undefined, function () {
            api.installed = true;
            api.installInProgress = false;
          }, function () {
            api.installed = false;
            api.installInProgress = false;
            api.error = true;
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
