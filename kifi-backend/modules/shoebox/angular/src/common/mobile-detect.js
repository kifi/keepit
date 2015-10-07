'use strict';

angular.module('kifi')

.factory('mobileOS', function () {
  var userAgent = navigator.userAgent || navigator.vendor || window.opera;

  if (userAgent.match(/Kifi/i)) {
    return 'Kifi';
  } if( userAgent.match( /iPad/i ) || userAgent.match( /iPhone/i ) || userAgent.match( /iPod/i ) ) {
    return 'iOS';
  } else if( userAgent.match( /Android/i ) ) {
    return 'Android';
  } else {
    return 'unknown';
  }
});
