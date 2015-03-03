'use strict';

angular.module('kifi')

.directive('kfLibraryFollowerPics', [
  '$window', '$filter', 'platformService', 'util',
  function ($window, $filter, platformService, util) {
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
          scope.moreCountText = $filter('num')(n - numToShow);

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
      }
    };
  }
]);
