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

    $scope.triggerOnboarding = function () {
      $window.postMessage({
        type: 'start_guide',
        pages: [{
          url: 'http://realhealthyrecipes.com/2013/09/25/frosted-watermelon-cake/',
          title: ['Frosted','Watermelon','Cake'],
          site: 'realhealthyrecipes.com',
          thumb: '/img/guide/watermelon_cake.jpg',
          noun: 'recipe',
          tag: 'Recipe',
          query: 'watermelon',
          matches: {title: [[8,10]], url: [[42,10]]}
        }, {
          url: 'https://www.etsy.com/listing/163215077/large-leather-tote-everyday-tote-bag',
          title: ['Large','Leather','Tote'],
          site: 'etsy.com',
          thumb: '/img/guide/leather_tote.jpg',
          noun: 'tote',
          tag: 'Shopping Wishlist',
          query: 'tote',
          matches: {title: [[14,4]], url: [[45,4],[59,4]]}
        }, {
          url: 'http://www.lifehack.org/articles/communication/10-things-people-who-truly-love-their-lives-differently.html',
          title: ['10 Things','People Who Truly','Love Their Lives','Do Differently'],
          site: 'lifehack.org',
          thumb: '/img/guide/love_life.jpg',
          noun: 'article',
          tag: 'Read Later',
          query: 'love+life',
          matches: {title: [[27,4]], url: [[67,4]]}
        }, {
          url: 'http://www.ted.com/talks/steve_jobs_how_to_live_before_you_die',
          title: ['Steve Jobs:','How to Live','Before You Die'],
          site: 'ted.com',
          thumb: '/img/guide/before_you_die.jpg',
          noun: 'video',
          tag: 'Inspiration',
          query: 'steve+jobs',
          matches: {title: [[0,5],[6,4]], url: [[18,5],[24,4]]}
        }]
      }, '*');
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
