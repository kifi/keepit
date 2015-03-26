'use strict';

angular.module('kifi')

.directive('kfLibraryFollowerPics', [
  '$window', '$filter', 'util',
  function ($window, $filter, util) {
    return {
      restrict: 'A',
      templateUrl: 'libraries/libraryFollowerPics.tpl.html',
      scope: {
        followers: '=kfLibraryFollowerPics',
        numFollowers: '=',
        followerWidths: '=',  // pic period (width + gap) for each window width range (should match stylesheet)
        showFollowers: '&',
        tooltips: '@',
        currentPageOrigin: '@'
      },
      link: function (scope, element) {
        function calcPicWidths(winWidth) {
          var widths = scope.followerWidths;
          for (var i = 1; i < widths.length && winWidth >= widths[i][0]; i++); // jshint ignore:line
          var arr = widths[i-1];
          return {gap: arr[2], period: arr[1] + arr[2]};
        }

        function adjustFollowerPicsSize() {
          var n = Math.max(scope.numFollowers, scope.followers.length);  // tolerating incorrect numFollowers
          var maxWidth = element[0].clientWidth;
          var picWidths = calcPicWidths(window.innerWidth);

          var numCanFit = Math.floor((maxWidth + picWidths.gap) / picWidths.period);
          var showPlus = numCanFit < n;
          var numToShow = Math.min(scope.followers.length, numCanFit - (showPlus ? 1 : 0));

          scope.followersToShow = scope.followers.slice(0, numToShow);
          scope.moreCount = n - numToShow;
          scope.moreCountText = showPlus ? $filter('num')(n - numToShow) : '';
        }

        //
        // Watches and listeners
        //
        scope.$watch('numFollowers', function (newVal, oldVal) {
          if (newVal === oldVal) {
            // on the first call, wait for templates to be substituted to get correct width measurement
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
