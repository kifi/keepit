'use strict';

angular.module('kifi.routeService', [])

.factory('routeService', [
  'env',
  function (env) {
    function route(url) {
      return env.xhrBase + url;
    }

    function formatPicUrl(userId, pictureName, size) {
      return env.picBase + '/users/' + userId + '/pics/' + (size || 200) + '/' + pictureName;
    }

    return {
      profileUrl: route('/user/me'),
      emailInfoUrl: route('/user/email'),
      abooksUrl: route('/user/abooks'),
      resendVerificationUrl: route('/user/resend-verification'),
      userPasswordUrl: route('/user/password'),
      formatPicUrl: formatPicUrl
    };
  }
]);
