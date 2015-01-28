'use strict';

angular.module('kifi')

.controller('RightColCtrl', [
  '$scope', '$element', '$window', 'modalService', 'profileService', '$q', '$http', 'env', '$timeout',
  'installService', '$rootScope', '$analytics', 'friendService', 'socialService', '$location', 'tagService',
  function ($scope, $element, $window, modalService, profileService, $q, $http, env, $timeout,
    installService, $rootScope, $analytics, friendService, socialService, $location, tagService) {
    $scope.data = $scope.data || {};
    $scope.me = profileService.me;
    var friendsReady = false;

    friendService.getKifiFriends().then(function () {
      friendsReady = true;
    });
    $timeout(function () {
      friendsReady = true;
    }, 1200);

    // Temp callout method. Remove after most users know about libraries. (Oct 26 2014)
    var calloutName = 'guide_callout_shown';
    $scope.showCallout = function () {
      return profileService.prefs.site_intial_show_library_intro && !profileService.prefs[calloutName];
    };
    $scope.closeCalloutOpenGuide = function () {
      $scope.closeCallout();
      $scope.triggerGuide(true);
    };
    $scope.closeCallout = function () {
      var save = { 'site_show_library_intro': false };
      save[calloutName] = true;
      profileService.prefs[calloutName] = true;
      profileService.savePrefs(save);
    };

    $scope.readyToDraw = function () {
      return profileService.userLoggedIn() === true && profileService.me.seqNum > 0 && friendsReady;
    };

    socialService.refresh().then(function () {
      $scope.hasNoFriendsOrConnections = function () {
        return (friendService.totalFriends() === 0) && (socialService.networks.length === 0);
      };
    });

    $scope.openHelpRankHelp = function () {
      $scope.data.showHelpRankHelp = true;
      $analytics.eventTrack('user_viewed_page', {
        'type': 'HelpRankHelp'
      });
    };

    $scope.yesLikeHelpRank = function () {
      $scope.data.showHelpRankHelp = false;
      $analytics.eventTrack('user_clicked_page', {
        'type': 'HelpRankHelp',
        'action': 'yesLikeHelpRank'
      });
    };

    $scope.noLikeHelpRank = function () {
      $scope.data.showHelpRankHelp = false;
      $analytics.eventTrack('user_clicked_page', {
        'type': 'HelpRankHelp',
        'action': 'noLikeHelpRank'
      });
    };

    $scope.trackClickOnClicks = function() {
      $analytics.eventTrack('user_clicked_page', {
        'action': 'helpRankClicks',
        'path': $location.path()
      });
    };

    $scope.trackClickOnReKeeps = function() {
      $analytics.eventTrack('user_clicked_page', {
        'action': 'helpRankReKeeps',
        'path': $location.path()
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
        modalService.open({
          template: 'common/modal/installExtensionErrorModal.tpl.html'
        });
      });
    };

    $scope.triggerGuide = function (linkClicked) {
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
          matches: {title: [[8,10]], url: [[49,10]]},
          track: 'watermelonCake'
        }, {
          url: 'https://www.etsy.com/listing/163215077/large-leather-tote-everyday-tote-bag',
          name: ['Large','Leather','Tote'],
          site: 'etsy.com',
          thumb: '/img/guide/leather_tote.jpg',
          noun: 'tote',
          tag: 'Wishlist',
          query: 'large+bag',
          title: 'Large Leather Tote - Everyday tote bag',
          matches: {title: [[0,5],[35,3]], url: [[39,5],[72,3]]},
          track: 'leatherTote'
        }, {
          url: 'http://www.ted.com/talks/steve_jobs_how_to_live_before_you_die',
          name: ['Steve Jobs:','How to Live','Before You Die'],
          site: 'ted.com',
          thumb: '/img/guide/before_you_die.jpg',
          image: ['/img/guide/ted_jobs.jpg', 480, 425],
          noun: 'video',
          tag: 'Inspiration',
          query: 'steve+jobs',
          title: 'Steve Jobs: How to live before you die | Talk Video | TED.com',
          matches: {title: [[0,5],[6,4]], url: [[25,5],[31,4]]},
          track: 'steveJobsSpeech'
        }]
      }, '*');
      if (linkClicked) {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'startGuide',
          'path': $location.path()
        });
      }
    };

    $scope.$on('relatedLibrariesChanged', function (e, relatedLibraries) {
      $scope.relatedLibraries = relatedLibraries;
    });

    var deregisterStateChange = $scope.$on('$stateChangeSuccess', function (event, toState) {
      // the current page is not a library, remove related libraries
      if (!toState.name.match(/^library\./)) {
        $scope.relatedLibraries = [];
      }
    });
    $scope.$on('$destroy', deregisterStateChange);

    $scope.$watch(function () {
      return Boolean(installService.installedVersion && profileService.prefs.auto_show_guide);
    }, function (show) {
      if (show) {
        $scope.triggerGuide();
        profileService.savePrefs({auto_show_guide: null});
      }
    });

    $scope.importBookmarks = function () {
      var kifiVersion = $window.document.documentElement.getAttribute('data-kifi-ext');

      if (!kifiVersion) {
        modalService.open({
          template: 'common/modal/installExtensionModal.tpl.html',
          scope: $scope
        });
        return;
      }

      $rootScope.$emit('showGlobalModal', 'importBookmarks');
      $analytics.eventTrack('user_viewed_page', {
        'type': 'browserImport'
      });
    };

    $scope.importBookmarkFile = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
      $analytics.eventTrack('user_viewed_page', {
        'type': '3rdPartyImport'
      });
    };

    var refreshTimeout;
    $window.addEventListener('message', function (event) {
      $scope.$apply(function () {
        var data = event.data || '';
        switch (data.type || data) {
          case 'get_guide':
            $scope.triggerGuide();
            break;
          case 'import_bookmarks':
            if (data.count > 0) {
              $rootScope.$emit('showGlobalModal', 'importBookmarks', data.count, event);
            }
            break;
          case 'update_keeps':
          case 'update_tags':
            $timeout.cancel(refreshTimeout);
            refreshTimeout = $timeout(function () {
              tagService.fetchAll(true);
            }, 1000); // Giving enough time for the services to be updated
            break;
        }
      });
    });

    var deregisterInstallExt = $rootScope.$on('triggerExtensionInstall', function() {
      $scope.triggerInstall();
    });
    $scope.$on('$destroy', deregisterInstallExt);
  }
]);
