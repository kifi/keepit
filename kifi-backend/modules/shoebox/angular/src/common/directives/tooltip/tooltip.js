'use strict';

angular.module('kifi')

.directive('kfTooltip', ['$window', '$timeout',
  function ($window, $timeout) {
    return {
      restrict: 'A',
      transclude: true,
      templateUrl: 'common/directives/tooltip/tooltip.tpl.html',
      replace: true,
      link: function (scope, element, attrs) {

        var container = element.parent();
        container.addClass("kf-tooltipped");
        var $w = angular.element($window);

        var el = element;

        el.css('padding', '' + (attrs.padding || 0) + 'px');


        function fullyOnScreen(){
          var viewportWidth = $w.innerWidth();
          var viewportHeight = $w.innerHeight();
          var elemPos = el[0].getBoundingClientRect();
          return elemPos.top > 0 && elemPos.bottom < viewportHeight && elemPos.left > 0 && elemPos.right < viewportWidth;
        }

        function applyPosition(pos) {
          var parentPos = container[0].getBoundingClientRect();
          switch (pos) {
            case 'top':
              el.css({
                'top': parentPos.top - el.outerHeight(),
                'left': parentPos.left - 0.5*el.outerWidth() + 0.5*container.outerWidth()
              });
              break;
            case 'bottom':
              el.css({
                'top': parentPos.top + container.outerHeight(),
                'left': parentPos.left - 0.5*el.outerWidth() + 0.5*container.outerWidth()
              });
              break;
            case 'left':
              el.css({
                'top': parentPos.top - 0.5*el.outerHeight() + 0.5*container.outerHeight(),
                'left': parentPos.left - el.outerWidth()
              });
              break;
            case 'right':
              el.css({
                'top': parentPos.top - 0.5*el.outerHeight() + 0.5*container.outerHeight(),
                'left': parentPos.left + container.outerWidth()
              });
              break;
          }
        }


        function ensureCorrectPositioning() {
          var pos = attrs.position;

          var poss = _.filter(['top', 'right', 'bottom', 'left'], function (p) {
            return p!=pos;
          });
          poss.push(pos)

          applyPosition(pos);

          for (var i=0; i<poss.length && !fullyOnScreen(); i++) {
            applyPosition(poss[i]);
          }




        }

        function trackScroll() {
          if (el.css('visibility') === 'visible') {
            ensureCorrectPositioning();
          }
        }

        container.on('mouseenter', ensureCorrectPositioning);
        $w.on('scroll', trackScroll);

        scope.$on('$destroy', function () {
          container.off("mouseenter", ensureCorrectPositioning);
          $w.off('scroll', trackScroll);
        });

      }
    };
  }
]);
