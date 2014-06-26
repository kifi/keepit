'use strict';

angular.module('kifi.layout.rightCol', ['kifi.modal'])

.controller('RightColCtrl', [
  '$scope', '$element', '$window', 'profileService', '$q', '$http', 'env', '$timeout',
  'installService', '$rootScope', '$analytics', 'friendService', '$location',
  function ($scope, $element, $window, profileService, $q, $http, env, $timeout, installService, $rootScope, $analytics, friendService, $location) {
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

    $scope.showClicks = function() {
      $location.path('/helprank/click');
      $analytics.eventTrack('user_clicked_page', {
        'action': 'helpRankClicks'
      });
      $analytics.eventTrack('user_viewed_page', {
        'action': 'helpRankClicks'
      });
    };

    $scope.showReKeeps = function() {
      $location.path('/helprank/rekeep');
      $analytics.eventTrack('user_clicked_page', {
        'action': 'helpRankReKeeps'
      });
      $analytics.eventTrack('user_viewed_page', {
        'action': 'helpRankReKeeps'
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

    $scope.triggerGuide = function () {
      $window.postMessage({
        type: 'start_guide',
        pages: [{
          url: 'http://realhealthyrecipes.com/2013/09/25/frosted-watermelon-cake/',
          name: ['Frosted','Watermelon','Cake'],
          site: 'realhealthyrecipes.com',
          thumb: '/img/guide/watermelon_cake.jpg',
          noun: 'recipe',
          tag: 'Recipe',
          query: 'watermelon',
          title: 'Frosted Watermelon Cake | Real Healthy Recipes',
          matches: {title: [[8,10]], url: [[49,10]]}
        }, {
          url: 'https://www.etsy.com/listing/163215077/large-leather-tote-everyday-tote-bag',
          name: ['Large','Leather','Tote'],
          site: 'etsy.com',
          thumb: '/img/guide/leather_tote.jpg',
          noun: 'tote',
          tag: 'Wishlist',
          query: 'large+bag',
          title: 'Large Leather Tote - Everyday tote bag',
          matches: {title: [[0,5],[35,3]], url: [[39,5],[72,3]]}
        }, {
          url: 'http://www.lifehack.org/articles/communication/10-things-people-who-truly-love-their-lives-differently.html',
          name: ['10 Things','People Who Truly','Love Their Lives','Do Differently'],
          site: 'lifehack.org',
          thumb: '/img/guide/love_life.jpg',
          noun: 'article',
          tag: 'Read Later',
          query: 'love+life',
          title: '10 Things People Who Truly Love Their Lives Do Differently',
          matches: {title: [[27,4],[38,5]], url: [[11,4],[74,4],[85,5]]}
        }, {
          url: 'http://www.ted.com/talks/steve_jobs_how_to_live_before_you_die',
          name: ['Steve Jobs:','How to Live','Before You Die'],
          site: 'ted.com',
          thumb: '/img/guide/before_you_die.jpg',
          noun: 'video',
          tag: 'Inspiration',
          query: 'steve+jobs',
          title: 'Steve Jobs: How to live before you die | Talk Video | TED.com',
          matches: {title: [[0,5],[6,4]], url: [[25,5],[31,4]]}
        }]
      }, '*');
      delete $window.document.documentElement.dataset.guide;
    };
    if ('guide' in $window.document.documentElement.dataset) {
      $scope.triggerGuide();
    }

    $scope.importBookmarks = function () {
      var kifiVersion = $window.document.documentElement.dataset.kifiExt;

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
      switch (event.data) {
        case 'get_guide':
          $scope.triggerGuide();
          break;
        case 'import_bookmarks':
          if (event.data.bookmarkCount > 0) {
            $rootScope.$emit('showGlobalModal', 'importBookmarks', event.data.bookmarkCount, event);
          }
          break;
        case 'update_keeps':
          location.reload();
          break;
      }
    });

    $scope.logout = function () {
      profileService.logout();
    };
  }
]);
