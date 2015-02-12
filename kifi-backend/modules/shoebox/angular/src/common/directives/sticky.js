'use strict';

angular.module('kifi')

.directive('kfSticky', [
  '$window', '$timeout',
  function ($window, $timeout) {
    return {
      restrict: 'A',
      scope: {
        'stickyTop': '=',     // required, px from viewport top at which element becomes stuck
        'stickyToggle': '&',  // required, implements element stuck/unstuck state transitions
        'stickyNear': '&',    // optional, reacts to element approaching or leaving stuck position
        'stickyNearPx': '=',  // optional, px from stuck/unstuck threshold stickyNear cares about (default: 10)
        'stickyIf': '&'       // optional, disables this directive if falsy, evaluated only once
      },
      link: function (scope, element) {
        if (scope.stickyIf && !scope.stickyIf()) {
          return;
        }

        var stickyTop = scope.stickyTop;
        var stickyToggle = scope.stickyToggle();
        var stickyNear = scope.stickyNear && scope.stickyNear();
        var stickyNearPx = scope.stickyNearPx || 10;

        var $win = angular.element($window);
        var stuck;
        var pxFromDocTop;      // remembered to avoid calling getBoundingClientRect() too frequently
        var momentumInterval;  // used to detect when touch-based momentum scrolling has stopped
        var stickyNearToldPx;  // last px value passed to stickyNear, to ensure we call it a final time

        function measurePxFromDocTop() {
          if (!stuck) {
            var pxFromViewportTop = element[0].getBoundingClientRect().top;
            pxFromDocTop = pxFromViewportTop + $win.scrollTop();
          }
        }

        function handleNewScrollTop(scrollTop) {
          var pxFromThreshold = pxFromDocTop - scrollTop - stickyTop;
          if (pxFromThreshold <= 0) {
            if (!stuck) {
              if (stickyNear) {
                stickyNear(element, 0, stickyNearPx);
                stickyNearToldPx = 0;
              }
              stickyToggle(element, true);
              stuck = true;
            }
          } else {
            if (stuck) {
              stickyToggle(element, false);
              stuck = false;
            }
            if (stickyNear && (pxFromThreshold <= stickyNearPx || stickyNearToldPx < stickyNearPx)) {
              var px = Math.min(pxFromThreshold, stickyNearPx);
              stickyNear(element, px, stickyNearPx);
              stickyNearToldPx = px;
            }
          }
        }

        function onWheel(e) {
          handleNewScrollTop($win.scrollTop() + e.originalEvent.deltaY);
        }

        function onTouchMove(e) {
          clearInterval(momentumInterval);
          handleNewScrollTop($win.scrollTop() + e.originalEvent.deltaY);
        }

        function onTouchEnd() {
          var prevScrollTop = $win.scrollTop();
          var noChangeCount = 0;

          clearInterval(momentumInterval);
          momentumInterval = setInterval(function () {
            var scrollTop = $win.scrollTop();
            if (scrollTop === prevScrollTop) {
              if (++noChangeCount >= 5) {
                clearInterval(momentumInterval);
              }
            } else {
              handleNewScrollTop(scrollTop);
              prevScrollTop = scrollTop;
            }
          }, 1000 / 60);
        }

        //
        // Initialization
        //

        $timeout(function () {  // timeout needed for mobile Safari
          var onScroll = _.throttle(measurePxFromDocTop, 200, {leading: true});

          // changes to the page may change the element's position, so measure it periodically
          $win.on('scroll', onScroll);
          $win.on('wheel', onWheel);
          $win.on('touchmove', onTouchMove);
          $win.on('touchend', onTouchEnd);

          scope.$on('$destroy', function () {
            $win.off('scroll', onScroll);
            $win.off('wheel', onWheel);
            $win.off('touchmove', onTouchMove);
            $win.off('touchend', onTouchEnd);
          });
        });
      }
    };
  }
]);
