'use strict';

angular.module('kifi')

.directive('kfLibraryHeaderFollowButtonCta', [
  '$rootScope', '$timeout', '$window', 'libraryService',
  function ($rootScope, $timeout, $window, libraryService) {
    return {
      restrict: 'A',
      scope: {
        library: '='
      },
      templateUrl: 'libraries/libraryHeaderFollowButtonCta.tpl.html',
      link: function (scope, element) {

        //
        // Helper functions
        //

        function show(opts) {
          element
            .removeClass(opts.below ? 'kf-above' : 'kf-below')
            .addClass(opts.below ? 'kf-below' : 'kf-above');

          scope.whyShowing = opts.auto ? 'timer' : 'hover';
          clearShowTimeout();
          $window.removeEventListener('visibilitychange', onVisibilityChangeBeforeShow);

          libraryService.trackEvent('visitor_viewed_page', scope.library, {
            type: 'libraryLanding',
            viewType: 'hover',
            subtype: 'hoverFollowButton',
            trigger: opts.auto ? 'timer' : 'hover'
          });
        }

        function hide(manually) {
          scope.whyShowing = hideIfScrollTop = null;

          if (manually) {
            libraryService.trackEvent('visitor_clicked_page', scope.library, {
              type: 'libraryLanding',
              action: 'clickedFollowCTAClose'
            });
          }
        }

        function attemptAutoShowAfter(ms) {
          clearShowTimeout();
          showTimeout = $timeout(autoShowAsap, ms);
        }

        function autoShowAsap() {
          clearShowTimeout();
          if (!$window.document.hidden) {
            var buttonRect = measureButtonRect();
            var canFit = calcWhereCanFit(buttonRect);
            if (canFit.above && !(scrollInfo.deltaY > 0 && Date.now() - scrollInfo.time < 100)) {  // if not scrolling up
              show({auto: true});
              var lowerBound = scrollInfo.top + buttonRect.top - minSpace;
              hideIfScrollTop = function (scrollTop) {
                return scrollTop > lowerBound;
              };
            } else if (canFit.below && !canFit.above) {
              show({auto: true, below: true});
              var upperBoundPlusWinHeight = scrollInfo.top + buttonRect.bottom + minSpace;
              hideIfScrollTop = function (scrollTop) {
                return scrollTop < upperBoundPlusWinHeight - winHeight;
              };
            } else {
              showTimeout = $timeout(autoShowAsap, 500);
            }
          }
        }

        function clearShowTimeout() {
          if (showTimeout) {
            $timeout.cancel(showTimeout);
          }
          showTimeout = null;
        }

        function measureButtonRect() {
          return button[0].getBoundingClientRect();
        }

        function calcWhereCanFit(buttonRect) {
          buttonRect = buttonRect || measureButtonRect();
          var fromBottom = $window.innerHeight - buttonRect.bottom;
          return {
            above: buttonRect.top > minSpace && fromBottom > 0,
            below: fromBottom > minSpace && buttonRect.top > 0
          };
        }


        //
        // Scope methods
        //

        scope.onClickX = function () {
          hide(true);
        };


        //
        // Event handlers
        //

        function onParentMouseEnter() {
          if (!scope.whyShowing) {
            scope.$apply(function () {
              show({below: !calcWhereCanFit().above});  // prefer showing above
            });
          }
        }

        function onParentMouseLeave() {
          if (scope.whyShowing === 'hover') {
            scope.$apply(function () {
              hide();
            });
          }
        }

        function onScroll() {
          var top = $window.pageYOffset;
          scrollInfo.time = Date.now();
          scrollInfo.deltaY = top - scrollInfo.top;
          scrollInfo.top = top;

          if (hideIfScrollTop && hideIfScrollTop(top)) {
            hide();
          }
        }

        function onWinResize() {
          winHeight = $window.innerHeight;

          if (hideIfScrollTop && hideIfScrollTop($window.pageYOffset)) {
            hide();
          }
        }

        function onVisibilityChangeBeforeShow() {
          if ($window.document.hidden) {
            clearShowTimeout();
          } else {
            attemptAutoShowAfter(2000);
          }
        }


        //
        // Initialization
        //

        var showTimeout;
        var minSpace = 210;
        var hideIfScrollTop;
        var scrollInfo = {top: $window.pageYOffset};
        var winHeight = $window.innerHeight;

        if (!$window.document.hidden) {
          attemptAutoShowAfter(5000);
        }

        var button = element.next('.kf-lh-follow-btn');
        button.on('mouseenter', onParentMouseEnter);
        button.on('mouseleave', onParentMouseLeave);
        $window.addEventListener('scroll', onScroll);
        $window.addEventListener('resize', onWinResize);
        $window.addEventListener('visibilitychange', onVisibilityChangeBeforeShow);
        scope.$on('$destroy', function () {
          button.off('mouseenter', onParentMouseEnter);
          button.off('mouseleave', onParentMouseLeave);
          $window.removeEventListener('scroll', onScroll);
          $window.removeEventListener('resize', onWinResize);
          $window.removeEventListener('visibilitychange', onVisibilityChangeBeforeShow);
        });

        scope.$on('$destroy', $rootScope.$on('$stateChangeStart', function () {  // e.g. library search
          if (scope.whyShowing === 'timer') {
            hide();
          } else {
            clearShowTimeout();
          }
        }));

      }
    };
  }
]);
