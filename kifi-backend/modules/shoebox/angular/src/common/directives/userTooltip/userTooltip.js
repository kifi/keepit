'use strict';

angular.module('kifi')

.directive('kfUserTooltip', [
  '$window', '$timeout', '$rootElement', '$compile', '$templateCache',
  function ($window, $timeout, $rootElement, $compile, $templateCache) {
    return {
      restrict: 'A',
      scope: {
        user: '=kfUserTooltip',
        desc: '@'
      },
      link: function (scope, element) {
        var tooltip;
        var timeout;
        var touchedAt;

        scope.showing = false;

        element.on('mouseenter', function () {
          if (Date.now() - touchedAt < 1000) {
            return;
          }
          if (!tooltip) {
            tooltip = angular.element($templateCache.get('common/directives/userTooltip/userTooltip.tpl.html'));
            element.append(tooltip);
            $compile(tooltip)(scope);
          }
          timeout = $timeout(function () {
            scope.showing = true;
          }, 50);
        }).on('touchstart touchend', function () {
          touchedAt = Date.now();
        }).on('mouseleave mousedown', function () {
          $timeout.cancel(timeout);
          scope.$apply(function () {
            scope.showing = false;
          });
        });

        scope.$on('$destroy', function () {
          $timeout.cancel(timeout);
          if (tooltip) {
            tooltip.remove();
          }
        });
      }
    };
  }
]);
