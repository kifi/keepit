'use strict';

angular.module('kifi.keepWhoPics', ['kifi.keepWhoService'])

.directive('kfKeepWhoPic', [
  '$window', '$timeout', '$rootElement', '$compile', '$templateCache', 'keepWhoService',
  function ($window, $timeout, $rootElement, $compile, $templateCache, keepWhoService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoPic.tpl.html',
      scope: {
        keeper: '='
      },
      link: function (scope, element) {
        scope.tooltipEnabled = false;
        scope.getPicUrl = keepWhoService.getPicUrl;
        scope.getName = keepWhoService.getName;
        var tooltip = null;

        scope.showTooltip = function () {
          if (!tooltip) {
            // Create tooltip
            tooltip = angular.element($templateCache.get('common/directives/keepWho/friendCard.tpl.html'));
            $rootElement.append(tooltip);
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
          tooltip.css({left: left + 'px', top: top + 'px', width: tooltip.width() + 'px'});
          triangle.css({left: triangleLeft});

          $timeout(function () {
            scope.tooltipEnabled = true;
          });
        };

        scope.hideTooltip = function () {
          scope.tooltipEnabled = false;
        };
      }
    };
  }
])

.directive('kfKeepWhoPics', [
  'keepWhoService',
  function (keepWhoService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoPics.tpl.html',
      scope: {
        me: '=',
        keepers: '='
      },
      link: function (scope) {
        scope.getPicUrl = keepWhoService.getPicUrl;
        scope.getName = keepWhoService.getName;
      }
    };
  }
]);
