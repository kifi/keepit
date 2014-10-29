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

        var visible = false;

        var container = element.parent();
        container.addClass('kf-tooltipped');
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

          function setCssPos(top, left) {
            el.css({
              'top': top,
              'left': left
            });
          }

          switch (pos) {
            case 'top':
              setCssPos(parentPos.top - el.outerHeight(), parentPos.left - 0.5*el.outerWidth() + 0.5*container.outerWidth());
              break;
            case 'bottom':
              setCssPos(parentPos.top + container.outerHeight(), parentPos.left - 0.5*el.outerWidth() + 0.5*container.outerWidth());
              break;
            case 'left':
              setCssPos(parentPos.top - 0.5*el.outerHeight() + 0.5*container.outerHeight(), parentPos.left - el.outerWidth());
              break;
            case 'right':
              setCssPos(parentPos.top - 0.5*el.outerHeight() + 0.5*container.outerHeight(), parentPos.left + container.outerWidth());
              break;
            case 'top-right':
              setCssPos(parentPos.top - el.outerHeight(), parentPos.left + container.outerWidth());
              break;
            case 'top-left':
              setCssPos(parentPos.top - el.outerHeight(), parentPos.left - el.outerWidth());
              break;
            case 'bottom-right':
              setCssPos(parentPos.top + container.outerHeight(), parentPos.left + container.outerWidth());
              break;
            case 'bottom-left':
              setCssPos(parentPos.top + container.outerHeight(), parentPos.left - el.outerWidth());
              break;

          }
        }


        function ensureCorrectPositioning() {
          var pos = attrs.position;

          var poss = _.filter(['top', 'right', 'bottom', 'left', 'top-right', 'top-left', 'bottom-right', 'bottom-left'], function (p) {
            return p!==pos;
          });
          poss.push(pos);

          applyPosition(pos);

          for (var i=0; i<poss.length && !fullyOnScreen(); i++) {
            applyPosition(poss[i]);
          }
        }

        function trackScroll() {
          if (visible && el.css('visibility') === 'visible') {
            ensureCorrectPositioning();
          }
        }

        function onMouseEnter() {
          visible = true;
          $timeout(function (){
            el.css({ //WARNING HACK (causing the element to be layed out so I can get the size correctly in the next event. Better ideas appreciated.)
              top: '-542px',
              left: '-542px'
            });
            $timeout(ensureCorrectPositioning);
          });
        }

        function onMouseLeave() {
          visible = false;
        }

        container.on('mouseenter', onMouseEnter);
        container.on('mouseleave', onMouseLeave);

        var debouncedScroll = _.debounce(trackScroll, 50);
        $w.on('scroll', debouncedScroll);

        scope.$on('$destroy', function () {
          container.off('mouseenter', ensureCorrectPositioning);
          container.off('mouseleave', onMouseLeave);
          $w.off('scroll', debouncedScroll);
        });

        scope.showing = function () {
          return visible;
        };

      }
    };
  }
]);
