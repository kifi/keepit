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
      triggerInstall: function () {
        if (isChrome && supported) {
          api.installInProgress = true;
          $window.chrome.webstore.install('https://chrome.google.com/webstore/detail/fpjooibalklfinmkiodaamcckfbcjhin', function () {
            console.log("success");
            api.installed = true;
            api.installInProgress = false;
            api.error = false;
            $rootScope.$digest();
          }, function (e) {
            $log.log(e);
            api.installed = false;
            api.installInProgress = false;
            api.error = true;
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
