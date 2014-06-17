'use strict';

angular.module('kifi.layout.rightCol', ['kifi.modal'])

.controller('RightColCtrl', [
  '$scope', '$element', '$window', 'profileService', '$q', '$http', 'env', '$timeout', 'installService', '$rootScope', '$analytics', 'friendService',
  function ($scope, $element, $window, profileService, $q, $http, env, $timeout, installService, $rootScope, $analytics, friendService) {
    $scope.data = $scope.data || {};
    $scope.me = profileService.me;
    var friendsReady = false;

    friendService.getKifiFriends().then(function () {
      friendsReady = true;
    });
    $timeout(function () {
      friendsReady = true;
    }, 1200);

    $scope.readyToDraw = function () {
      return profileService.me.seqNum > 0 && friendsReady;
    };

    $scope.openHelpRankHelp = function () {
      $scope.data.showHelpRankHelp = true;
      $analytics.eventTrack('user_viewed_page', {
        'type': 'HelpRankHelp'
      });
    };

    $scope.yesLikeHelpRank = function () {
      $scope.data.showHelpRankHelp = false;
      $analytics.eventTrack('user_clicked_page', {
        'action': 'yesLikeHelpRank'
      });
    };

    $scope.noLikeHelpRank = function () {
      $scope.data.showHelpRankHelp = false;
      $analytics.eventTrack('user_clicked_page', {
        'action': 'noLikeHelpRank'
      });
    };

    $scope.installInProgress = function () {
      return installService.installInProgress;
    };

    $scope.installed = function () {
      return installService.installed;
    };

    $scope.installError = function () {
      return installService.error;
    };

    $scope.triggerInstall = function () {
      installService.triggerInstall(function () {
        $rootScope.$emit('showGlobalModal', 'installExtensionError');
      });
    };

    // onboarding.js is using these functions
    $window.getMe = function () {
      return (profileService.me ? $q.when(profileService.me) : profileService.getMe()).then(function (me) {
        me.pic200 = me.picUrl;
        return me;
      });
    };

    $window.exitOnboarding = function () {
      $scope.data.showGettingStarted = false;
      $http.post(env.xhrBase + '/user/prefs', {
        onboarding_seen: 'true'
      });
      if (!profileService.prefs.onboarding_seen) {
        $scope.importBookmarks();
      }
      $scope.$apply();
    };

    $rootScope.$on('showGettingStarted', function () {
      $scope.data.showGettingStarted = true;
    });

    $scope.importBookmarks = function () {
      var kifiVersion = $window.document.getElementsByTagName('html')[0].getAttribute('data-kifi-ext');

      if (!kifiVersion) {
        $rootScope.$emit('showGlobalModal','installExtension');
        return;
      }

      $rootScope.$emit('showGlobalModal', 'importBookmarks');
    };

    $scope.importBookmarkFile = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
    };

    $window.addEventListener('message', function (event) {
      if (event.data && event.data.bookmarkCount > 0) {
        $rootScope.$emit('showGlobalModal', 'importBookmarks', event.data.bookmarkCount, event);
      }
    });

    $scope.logout = function () {
      profileService.logout();
    };
  }
]);
