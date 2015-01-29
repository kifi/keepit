'use strict';

angular.module('kifi')

.directive('kfLibraryFollowerPics', [
  '$window', 'platformService',
  function ($window, platformService) {
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
      link: function (scope, element /*, attrs */) {
        function init() {
          scope.followersToShow = [];
          scope.numAdditionalFollowers = 0;
        }

        function adjustFollowerPicsSize() {
          var parentWidth = element.parent().width();

          var siblingsWidth = 0;
          element.siblings().each(function () {
            siblingsWidth += this.offsetWidth;
          });

          var additionalNeededWith = 70; // the additional number of followers, etc.
          var widthPerFollowerPic = 50;

          if (platformService.isSupportedMobilePlatform()) {
            siblingsWidth = 0;
            additionalNeededWith = 120;
            widthPerFollowerPic = 110;
          }

          var maxFollowersToShow = Math.floor((parentWidth - siblingsWidth - additionalNeededWith) / widthPerFollowerPic);
          // If we only have one additional follower that we can't fit in, then we can fit that one
          // in if we don't show the additional-number-of-followers circle.
          if (maxFollowersToShow === scope.numFollowers - 1) {
            maxFollowersToShow++;
          }

          scope.numAdditionalFollowers = 0;

          scope.$evalAsync(function () {
            if (maxFollowersToShow < 1) {
              scope.followersToShow = [];
              scope.numAdditionalFollowers = scope.numFollowers;
            } else if (maxFollowersToShow >= scope.numFollowers) {
              scope.followersToShow = scope.followers;
              scope.numAdditionalFollowers = 0;
            } else {
              scope.followersToShow = scope.followers.slice(0, maxFollowersToShow);
              scope.numAdditionalFollowers = scope.numFollowers - maxFollowersToShow;
            }

            element.find('.kf-keep-lib-follower-pics')
              .width(maxFollowersToShow >= 1 ? maxFollowersToShow * widthPerFollowerPic : 0);
          });
        }


        //
        // Watches and listeners.
        //
        scope.$watch('numFollowers', adjustFollowerPicsSize);

        // Update how many follower pics are shown when the window is resized.
        var adjustFollowerPicsSizeOnResize = _.debounce(adjustFollowerPicsSize, 200);
        $window.addEventListener('resize', adjustFollowerPicsSizeOnResize);
        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', adjustFollowerPicsSizeOnResize);
        });


        init();
      }
    };
  }
]);
