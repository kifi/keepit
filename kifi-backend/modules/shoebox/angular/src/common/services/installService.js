'use strict';

angular.module('kifi')

.factory('installService', ['$window', '$log', '$rootScope',
  function ($window, $log, $rootScope) {
    var isChrome = $window.chrome && $window.chrome.webstore && $window.chrome.webstore.install;
    var isFirefox = !isChrome && ('MozBoxSizing' in $window.document.documentElement.style) || ($window.navigator.userAgent.indexOf('Firefox') > -1);
    var isSafari = !isChrome && /Safari/.test(navigator.userAgent) && /Apple Computer/.test(navigator.vendor);
    var majorVersion = +($window.navigator.userAgent.match(/(?:Chrome|Firefox|Safari)\/(\d+)/) || [null, 999])[1];
    var supported = isChrome && majorVersion >= 32 || isFirefox && majorVersion >= 20 || isSafari && majorVersion >= 536;

    if (supported) {
      if (isChrome) {
        $window.document.head.insertAdjacentHTML(
          'beforeend', '<link rel="chrome-webstore-item" href="https://chrome.google.com/webstore/detail/fpjooibalklfinmkiodaamcckfbcjhin">');
      }
      $window.addEventListener('message', function (e) {
        var data = e.data || {};
        if (data.type === 'kifi_ext_listening') {
          $rootScope.$apply(function () {
            api.installedVersion = data.version;
            $rootScope.$emit('kifiExt');
          });
        }
      });
    }

    var api = {
      triggerInstall: function (onError) {
        if (isChrome && supported) {
          api.installState = 'installing';
          $window.chrome.webstore.install('https://chrome.google.com/webstore/detail/fpjooibalklfinmkiodaamcckfbcjhin', function () {
            $rootScope.$apply(function () {
              api.installState = 'done';
            });
          }, function (e) {
            $rootScope.$apply(function () {
              $log.log(e);
              api.installState = null;
              if (onError) {
                onError();
              }
            });
          });
        } else if (isFirefox && supported) {
          $window.InstallTrigger.install({
            Kifi: {
              URL: '/extensions/firefox/kifi.xpi',
              IconURL: '/extensions/firefox/kifi.png'
            }
          });
        } else if (isSafari && supported) {
          var win = $window.open('https://safari-extensions.apple.com/details/?id=com.fortytwo.kifi-V4GCE6T8A5', '_blank');
          win.focus();
        } else {
          $window.location.href = '/unsupported';
        }
      },
      canInstall: supported,
      installState: null,
      installedVersion: angular.element(document.documentElement).attr('data-kifi-ext'),
      hasMinimumVersion: function (minVersion, minCanaryVersion) {
        var version = api.installedVersion;
        if (!version) {
          return false;
        }
        if (!minVersion) {
          return true;
        }
        var parts = version.split('.');
        var minParts = (parts.length > 3 && minCanaryVersion || minVersion).split('.');
        for (var i = 0; i < parts.length; i++) {
          if (i >= minParts.length || +parts[i] > +minParts[i]) {
            return true;
          } else if (+parts[i] < +minParts[i]) {
            return false;
          }
        }
        return false;
      },
      isValidChrome: isChrome && supported,
      isValidFirefox: isFirefox && supported,
      isValidSafari: isSafari && supported,
      getPlatformName: function () {
        var platformName;
        if (api.canInstall) {
          if (api.isValidChrome) {
            platformName = 'Chrome';
          } else if (api.isValidFirefox) {
            platformName = 'Firefox';
          } else if (api.isValidSafari) {
            platformName = 'Safari';
          }
        }
        return platformName;
      }
    };

    return api;
  }
]);
