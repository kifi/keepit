'use strict';

angular.module('kifi')

.directive('kfLibraryFollowerPics', [
  '$window', 'platformService', 'util',
  function ($window, platformService, util) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'libraries/libraryFollowerPics.tpl.html',
      scope: {
        numFollowers: '=',
        followers: '=',
        showFollowers: '&',
        currentPageOrigin: '@'
      },
      link: function (scope, element) {
        var isMobile = platformService.isSupportedMobilePlatform();

        function adjustFollowerPicsSize() {
          var n = scope.numFollowers;
          var maxWidth = element[0].offsetWidth;
          var picWidth = isMobile ? 110 : 50;

          var numCanFit = Math.floor(maxWidth / picWidth);
          if (numCanFit < n) {
            numCanFit--;  // need to show +N circle
          }
          var numToShow = Math.min(scope.followers.length, numCanFit);

          scope.followersToShow = scope.followers.slice(0, numToShow);
          scope.moreCountText = formatMoreCountText(n - numToShow);

          element.find('.kf-lfp-pics').width(numToShow * picWidth);
        }

        function formatMoreCountText(n) {
          if (n < 1000) {
            return n ? String(n) : '';
          }
          var hundreds = String(n).slice(0, -2);
          return hundreds.slice(-1) === '0' ? hundreds.slice(0, -1) + 'K' : hundreds.replace(/(\d)$/, '.$1') + 'K';
        }

        //
        // Watches and listeners
        //
        scope.$watch('numFollowers', function (newVal, oldVal) {
          if (newVal === oldVal) {
            // on the first call, wait for counts to be inserted into DOM to get correct width measurement
            scope.$evalAsync(adjustFollowerPicsSize);
          } else {
            adjustFollowerPicsSize();
          }
        });

        // Update how many follower pics are shown when the window is resized.
        var onWinResize = util.$debounce(scope, adjustFollowerPicsSize, 200);
        $window.addEventListener('resize', onWinResize);
        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', onWinResize);
        });
      }
    };
  }
]);
