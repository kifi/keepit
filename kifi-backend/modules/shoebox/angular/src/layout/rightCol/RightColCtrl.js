'use strict';

angular.module('kifi.layout.rightCol', ['kifi.modal'])

.controller('RightColCtrl', [
  '$scope', '$element', '$window', 'profileService', '$q', '$http', 'env', '$timeout',
  function ($scope, $element, $window, profileService, $q, $http, env, $timeout) {
    $scope.data = {};

    // onboarding.js is using these functions
    $window.getMe = function() {
      return (profileService.me ? $q.when(profileService.me) : profileService.fetchMe()).done(function (me) {
        me.pic200 = me.picUrl;
        return me;
      });
    };

    $window.exitOnboarding = function () {
      $scope.data.showGettingStarted = false;
      $http.post(env.xhrBase + '/user/prefs', {
        onboarding_seen: 'true'
      });
      $scope.$apply();
      //initBookmarkImport();
    };

    var updateHeight = _.throttle(function () {
      $element.css('height', $window.innerHeight + 'px');
    }, 100);
    angular.element($window).resize(updateHeight);

    $timeout(updateHeight);
  }
]);
