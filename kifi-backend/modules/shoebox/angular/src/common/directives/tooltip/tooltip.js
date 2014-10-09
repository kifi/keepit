'use strict';

angular.module('kifi')

.directive('kfTooltip', ['$window', '$timeout',
  function ($window, $timeout) {
    return {
      restrict: 'A',
      transclude: true,
      templateUrl: 'common/directives/tooltip/tooltip.tpl.html',
      link: function (scope, element, attrs) {

        var container = element.parent();

        function ensureCorrectPositioning() {
          var el = angular.element(element.children()[0])

          console.log("blahhhhhhhh");

          switch (attrs.position) {
            case 'top':
              el.css('bottom',container.outerHeight());
              break;
            case 'bottom':
              el.css('top', container.outerHeight());
              break;
            case 'left':
              el.css('right', container.outerWidth());
              el.css('top', 0);
              break;
            case 'right':
              el.css('left', container.outerWidth());
              el.css('top', 0);
              break;
          }


        }

        container.on("mouseenter", ensureCorrectPositioning);

        scope.$on('$destroy', function () {
          container.off("mouseenter", ensureCorrectPositioning);
        });

      }
    };
  }
]);
