'use strict';

angular.module('kifi.layout.rightCol', ['kifi.modal'])

.controller('RightColCtrl', [
  '$scope', '$window',
  function ($scope, $window) {
    $scope.data = {};

    // onboarding.js are using this functions
    $window.getMe = function() {
      return promise.me.done(function (me) {
        me.pic200 = formatPicUrl(me.id, me.pictureName, 200);
        return me;
      });
    };

    $window.exitOnboarding = function () {
      $('.kifi-onboarding-iframe').remove();
      $.postJson(xhrBase + '/user/prefs', {
        onboarding_seen: 'true'
      }, function (data) {
        log('[prefs]', data);
      });
      initBookmarkImport();
    };

  }
]);
