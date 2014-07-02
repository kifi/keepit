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

        element.css('boxSizing', 'content-box');

        var marginLeft = getCssPixelProperty('marginLeft'),
          marginTop = getCssPixelProperty('marginTop'),
          marginRight = getCssPixelProperty('marginRight'),
          marginBottom = getCssPixelProperty('marginBottom'),
          offsetTop = element.offset().top - marginTop,
          offsetLeft = element.offset().left - marginLeft,
          width = element.width(),
          height = element.height();
        /*
          TODO: test & cover wider variety of cases:
          * border-box vs content-box
          * padding, border, margin
        }*/

        var filler = angular.element('<div />');
        filler.css({
          width: element.outerWidth() + marginLeft + marginRight,
          height: element.outerHeight() + marginTop + marginBottom,
          visibility: 'hidden'
        });

        function onScroll(e) {
          var originalWindowTopOffset = offsetTop - ($win.scrollTop() + e.originalEvent.deltaY);
          if (originalWindowTopOffset <= scope.maxTop) {
            if (!sticking) {
              element.css({
                position: 'fixed',
                top: scope.maxTop,
                left: offsetLeft,
                width: width,
                height: height,
                zIndex: 1
              });
              element.after(filler);
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
              sticking = false;
            }
          }
        }

        $win.on('mousewheel', onScroll);

        scope.$on('$destroy', function () {
          $win.off(onScroll);
        });
      }
    };
  }
]);
