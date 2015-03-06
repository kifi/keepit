'use strict';

angular.module('kifi')

.factory('extensionLiaison', [
  '$window', '$timeout', '$rootScope', 'installService', 'profileService',
  function ($window, $timeout, $rootScope, installService, profileService) {

    $window.addEventListener('message', function (event) {
      $rootScope.$apply(function () {
        var data = event.data || '';
        switch (data.type || data) {
          case 'get_guide':
            triggerGuide();
            break;
          case 'import_bookmarks':
            if (data.count > 0) {
              $rootScope.$emit('showGlobalModal', 'importBookmarks', data.count, event);
            }
            break;
          case 'update_keeps':
          case 'update_tags':
            // TODO
            break;
        }
      });
    });

    function triggerGuide() {
      $window.postMessage({
        type: 'start_guide',
        pages: [{  // TODO: remove pages once extension 3.3.41 is out of use
          url: 'http://www.ted.com/talks/steve_jobs_how_to_live_before_you_die',
          image: ['/img/guide/ted_jobs.jpg', 480, 425],
          noun: 'video',
          query: 'steve+jobs',
          title: 'Steve Jobs: How to live before you die | Talk Video | TED.com',
          matches: {title: [[0,5],[6,4]], url: [[25,5],[31,4]]},
          track: 'steveJobsSpeech'
        }]
      }, '*');
    }

    function postReply(message, event) {
      if (event && event.source && event.origin) {
        event.source.postMessage(message, event.origin);
      } else {
        $window.postMessage(message, '*');
      }
    }

    return {
      triggerGuide: triggerGuide,
      openDeepLink: function (url, locator) {
        $window.postMessage({type: 'open_deep_link', url: url, locator: locator}, '*');
      },
      importBookmarksTo: function (libraryId, event) {
        postReply({type: 'import_bookmarks', libraryId: libraryId}, event);
      },
      declineBookmarkImport: function (event) {
        postReply('import_bookmarks_declined', event);
      }
    };
  }
]);
