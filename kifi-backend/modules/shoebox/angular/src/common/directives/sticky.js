'use strict';

angular.module('kifi')

.directive('kfSticky', [
  '$window', '$timeout',
  function ($window, $timeout) {
    return {
      restrict: 'A',
      scope: {
        'maxTop': '=',
        'onStick': '=',
         // widthMode supports 'calculate' and 'inherit' values
         // - calculated - try to calculate and explicitly set the same width as the element had
         // - ignored    - does not do any calculations to determine width; style.width is untouched
        'stickyWidthMode': '@'
      },
      link: function (scope, element) {
        var $win = angular.element($window);
        var sticking = false;

        function getCssPixelProperty(name) {
          return parseInt(element.css(name), 10);
        }

        function onStickCallback(isSticking) {
          if (_.isFunction(scope.onStick)){
            scope.onStick(isSticking);
          }
        }

        var isWidthCalculated = scope.stickyWidthMode === 'calculated';

        var marginTop, marginLeft,
          borderLeftWidth, borderTopWidth, borderRightWidth, borderBottomWidth,
          offsetTop, offsetLeft,
          width, height;

        function isTouchEvent(e) {
          return e.originalEvent.touches && e.originalEvent.touches.length > 0;
        }

        function updateProperties() {
          marginLeft = getCssPixelProperty('marginLeft');
          marginTop = getCssPixelProperty('marginTop');
          borderLeftWidth = getCssPixelProperty('borderLeftWidth');
          borderTopWidth = getCssPixelProperty('borderTopWidth');
          borderRightWidth = getCssPixelProperty('borderRightWidth');
          borderBottomWidth = getCssPixelProperty('borderBottomWidth');
          offsetTop = element.offset().top - marginTop;
          offsetLeft = element.offset().left - marginLeft;
          if (isWidthCalculated) {
            width = element.width() + borderLeftWidth + borderRightWidth;
          }
          height = element.height() + borderTopWidth + borderBottomWidth;
        }
        updateProperties();
        /*
          TODO: test & cover wider variety of cases:
          * border-box vs content-box
          * padding, border, margin
        }*/

        var filler = element.clone();
        filler.css('visibility', 'hidden');

        var unregister;

        function onScroll(e) {
          var deltaY = e && e.originalEvent.deltaY || 0;
          var originalWindowTopOffset = offsetTop - $win.scrollTop() - deltaY;
          if (originalWindowTopOffset <= scope.maxTop) {
            if (!sticking) {
              updateProperties();
              originalWindowTopOffset = offsetTop - ($win.scrollTop() + deltaY);
              if (originalWindowTopOffset > scope.maxTop) {
                return;
              }

              var styles = {
                position: 'fixed',
                top: scope.maxTop,
                left: offsetLeft,
                height: height,
                zIndex: 501
              };
              if (isWidthCalculated) {
                styles.width = width;
              }

              element.css(styles);
              element.after(filler);

              unregister = scope.$watch(function () {
                return {
                  width: filler.width(),
                  left: filler.offset().left
                };
              }, function (attributes) {
                if (isWidthCalculated) {
                  element.css('width', attributes.width + borderLeftWidth + borderRightWidth);
                }
                element.css('left', attributes.left - marginLeft);
              }, true);

              sticking = true;
              onStickCallback(true);
            }
          } else {
            if (sticking) {
              element.css({
                position: '',
                top: '',
                left: '',
                width: '',
                height: '',
                zIndex: ''
              });

              filler.remove();

              unregister();

              sticking = false;
              onStickCallback(false);
            }
          }
        }

        var momentumInterval;

        function onScrollTouch(e) {
          clearInterval(momentumInterval);
          onScroll(e);
        }

        function onTouchEnd() {
          var lastScrollTop = $win.scrollTop();
          var zeroDeltaCount = 0;
          var deltaScrollTop;

          var momentumInterval = setInterval(function () {
            deltaScrollTop = lastScrollTop - $win.scrollTop();
            if (deltaScrollTop === 0) {
              zeroDeltaCount++;
            } else {
              onScroll();
              lastScrollTop = $win.scrollTop();
            }

            // allows the interval to keep running until we've counted 5 0 detlas
            // it's possible that the document is still scrolling even after the delta is zero
            if (zeroDeltaCount > 5) {
              clearInterval(momentumInterval);
            }
          }, 1000 / 60);

          // after 2 seconds it's assumed the
          setTimeout(function () {
            clearInterval(momentumInterval);
          }, 2000);
        }

        $win.on('mousewheel', onScroll);
        $win.on('touchmove', onScrollTouch);
        $win.on('touchend', onTouchEnd);

        scope.$on('$destroy', function () {
          $win.off('mousewheel', onScroll);
          $win.off('touchmove', onScrollTouch);
          $win.off('touchend', onTouchEnd);
        });
      }
    };
  }
]);
