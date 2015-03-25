'use strict';

angular.module('kifi')

.directive('kfLibraryFollowerPics', [
  '$window', '$filter', 'util',
  function ($window, $filter, util) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'libraries/libraryFollowerPics.tpl.html',
      scope: {
        followers: '=kfLibraryFollowerPics',
        numFollowers: '=',
        followerWidths: '=',  // pic period (width + gap) for each window width range (should match stylesheet)
        showFollowers: '&',
        currentPageOrigin: '@'
      },
      link: function (scope, element) {
        function calcPicWidth(winWidth) {
          var widths = scope.followerWidths;
          for (var i = 1; i < widths.length && winWidth >= widths[i][0]; i++);
          return widths[i-1][1];
        }

        function adjustFollowerPicsSize() {
          var n = Math.max(scope.numFollowers, scope.followers.length);  // tolerating incorrect numFollowers
          var maxWidth = element[0].offsetWidth;
          var picWidth = calcPicWidth(window.innerWidth);

          var numCanFit = Math.floor(maxWidth / picWidth);
          if (numCanFit < n) {
            numCanFit--;  // need to show +N circle
          }
          var numToShow = Math.min(scope.followers.length, numCanFit);

          scope.picWidth = picWidth;
          scope.followersToShow = scope.followers.slice(0, numToShow);
          scope.moreCountText = $filter('num')(n - numToShow);
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
