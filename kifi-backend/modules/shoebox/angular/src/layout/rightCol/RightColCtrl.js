'use strict';

angular.module('kifi.layout.rightCol', ['kifi.modal'])

.controller('RightColCtrl', [
  '$scope', '$element', '$window', 'profileService', '$q', '$http', 'env', '$timeout', 'installService', '$rootScope',
  function ($scope, $element, $window, profileService, $q, $http, env, $timeout, installService, $rootScope) {
    $scope.data = $scope.data || {};

    $scope.installInProgress = function () {
      return installService.installInProgress;
    };

    $scope.installed = function () {
      return installService.installed;
    };

    $scope.installError = function () {
      return installService.error;
    };

    $scope.triggerInstall = installService.triggerInstall;

    // onboarding.js is using these functions
    $window.getMe = function () {
      return (profileService.me ? $q.when(profileService.me) : profileService.fetchMe()).then(function (me) {
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

    $scope.importBookmarks = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarks');
    };

    $window.addEventListener('message', function (event) {
      if (event.data && event.data.bookmarkCount > 0) {
        $rootScope.$emit('showGlobalModal', 'importBookmarks', event.data.bookmarkCount, event);
      }
    });
    $window.postMessage('get_bookmark_count_if_should_import', '*'); // may get {bookmarkCount: N} reply message

    $scope.logout = function () {
      profileService.logout();
    };


    var updateHeight = _.throttle(function () {
      $element.css('height', $window.innerHeight + 'px');
    }, 100);
    angular.element($window).resize(updateHeight);

    $timeout(updateHeight);
  }
]);
