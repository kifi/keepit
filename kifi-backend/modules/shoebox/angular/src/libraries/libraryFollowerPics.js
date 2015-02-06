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
          var addWidth = picWidth * (n < 100 ? 1 : n < 1000 ? 1.1 : 1.2);  // for "+N"

          var numToShow = Math.floor(Math.max(0, maxWidth - addWidth) / picWidth);
          // If we only have one additional follower that we can't fit in, then we can fit that one
          // in if we don't show the additional-number-of-followers circle.
          if (numToShow === n - 1) {
            numToShow++;
          }
          numToShow = Math.min(scope.followers.length, numToShow);

          scope.followersToShow = scope.followers.slice(0, numToShow);
          scope.numAdditionalFollowers = n - numToShow;

          element.find('.kf-lfp-pics').width(numToShow * picWidth);
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


        //
        // Initialization
        //
        scope.followersToShow = [];
        scope.numAdditionalFollowers = 0;
      }
    };
  }
]);
