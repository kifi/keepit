'use strict';

angular.module('kifi')

.directive('kfWhoTooltip', [
  '$window', '$timeout', '$rootElement', '$compile', '$templateCache', 'keepWhoService',
  function ($window, $timeout, $rootElement, $compile, $templateCache, keepWhoService) {
    return {
      restrict: 'A',
      link: function (scope, element) {
        var tooltip = null;
        var timeout = null;

        scope.tooltipEnabled = false;
        scope.getPicUrl = keepWhoService.getPicUrl;
        scope.getName = keepWhoService.getName;
        scope.youText = '';

        // The following is messy. It accounts for the case where we're using
        // this directive directly on an image element in the library card.
        // TODO(yiping): make the directive work for that case w/o this messiness.
        // Probably needs mor research on scopes in Angular.
        if (!scope.keeper && scope.follower) {
          scope.keeper = scope.follower;
          if (scope.followerIsMe(scope.follower)) {
            scope.youText = 'You! You look great!';
          }
        }

        function cancelTimeout() {
          $timeout.cancel(timeout);
        }

        scope.$on('$destroy', function () {
          cancelTimeout();
          if (tooltip) {
            tooltip.remove();
          }
        });

        scope.showTooltip = function () {
          if (!tooltip) {
            // Create tooltip
            tooltip = angular.element($templateCache.get('common/directives/keepWho/friendCard.tpl.html'));
            $rootElement.find('html').append(tooltip);
            $compile(tooltip)(scope);
          }

          // Set position
          var triangleOffset = 42;
          var triangleWidth = 1;
          var triangle = tooltip.find('.kifi-fr-kcard-tri');
          var left = element.offset().left + element.width() / 2 - triangleOffset;
          var top = element.offset().top - 91;
          var triangleLeft = triangleOffset - triangleWidth;
          if ($window.innerWidth - left - tooltip.width() < 3) {
            left += 2 * triangleOffset - tooltip.width();
            triangleLeft = tooltip.width() - triangleOffset - triangleWidth;
          }
          tooltip.css({left: left + 'px', top: top + 'px', width: tooltip.width() + 'px', visibility: 'hidden'});
          triangle.css({left: triangleLeft});

          timeout = $timeout(function () {
            tooltip.css('visibility', 'visible');
            scope.tooltipEnabled = true;
          }, 500);
        };

        scope.hideTooltip = function () {
          cancelTimeout();
          scope.tooltipEnabled = false;
        };
      }
    };
  }
]);
