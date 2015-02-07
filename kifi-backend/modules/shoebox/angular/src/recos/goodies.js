'use strict';

angular.module('kifi')

.directive('kfGoodies', [
  '$rootScope', '$window', '$location', '$analytics', 'installService', 'modalService',
  function ($rootScope, $window, $location, $analytics, installService, modalService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'recos/goodies.tpl.html',
      link: function (scope) {

        scope.installInProgress = function () {
          return installService.installInProgress;
        };

        scope.installed = function () {
          return installService.installed;
        };

        scope.installError = function () {
          return installService.error;
        };

        scope.triggerInstall = function () {
          installService.triggerInstall(function () {
            modalService.open({
              template: 'common/modal/installExtensionErrorModal.tpl.html'
            });
          });
        };

        scope.triggerGuide = function (linkClicked) {
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

        scope.importBookmarks = function () {
          var kifiVersion = $window.document.documentElement.getAttribute('data-kifi-ext');

          if (!kifiVersion) {
            modalService.open({
              template: 'common/modal/installExtensionModal.tpl.html',
              scope: scope
            });
            return;
          }

          $rootScope.$emit('showGlobalModal', 'importBookmarks');
          $analytics.eventTrack('user_viewed_page', {
            'type': 'browserImport'
          });
        };

        scope.importBookmarkFile = function () {
          $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
          $analytics.eventTrack('user_viewed_page', {
            'type': '3rdPartyImport'
          });
        };

      }
    };
  }
]);
