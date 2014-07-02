'use strict';

angular.module('kifi.sticky', ['kifi.sticky'])

.directive('kfSticky', [
  '$window',
  function ($window) {
    return {
      restrict: 'A',
      scope: {
        'maxTop': '='
      },
      link: function (scope, element) {
        var $win = angular.element($window);
        var sticking = false;

        function getCssPixelProperty(name) {
          return parseInt(element.css(name), 10);
        }

        var marginTop, marginLeft,
          borderLeftWidth, borderTopWidth, borderRightWidth, borderBottomWidth,
          offsetTop, offsetLeft,
          width, height;

        function updateProperties() {
          marginLeft = getCssPixelProperty('marginLeft'),
          marginTop = getCssPixelProperty('marginTop'),
          borderLeftWidth = getCssPixelProperty('borderLeftWidth'),
          borderTopWidth = getCssPixelProperty('borderTopWidth'),
          borderRightWidth = getCssPixelProperty('borderRightWidth'),
          borderBottomWidth = getCssPixelProperty('borderBottomWidth'),
          offsetTop = element.offset().top - marginTop,
          offsetLeft = element.offset().left - marginLeft,
          width = element.width() + borderLeftWidth + borderRightWidth,
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
          var originalWindowTopOffset = offsetTop - ($win.scrollTop() + e.originalEvent.deltaY);
          if (originalWindowTopOffset <= scope.maxTop) {
            if (!sticking) {
              updateProperties();
              element.css({
                position: 'fixed',
                top: scope.maxTop,
                left: offsetLeft,
                width: width,
                height: height,
                zIndex: 1
              });

              element.after(filler);

              unregister = scope.$watch(function () {
                return {
                  width: filler.width(),
                  left: filler.offset().left
                };
              }, function (attributes) {
                element.css('width', attributes.width + borderLeftWidth + borderRightWidth);
                element.css('left', attributes.left - marginLeft);
              }, true);

              sticking = true;
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
            }
          }
        }

        $win.on('mousewheel', onScroll);

        scope.$on('$destroy', function () {
          $win.off('mousewheel', onScroll);
        });
      }
    };
  }
]);
