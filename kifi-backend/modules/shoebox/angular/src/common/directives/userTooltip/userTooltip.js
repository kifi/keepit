'use strict';

angular.module('kifi')

.directive('kfUserTooltip', [
  '$window', '$timeout', '$compile', '$templateCache', 'libraryService',
  function ($window, $timeout, $compile, $templateCache, libraryService) {

    function getAbsoluteBoundingRect(element) {
      var documentRect = $window.document.documentElement.getBoundingClientRect();
      var elementRect = (element[0] || element).getBoundingClientRect();

      return {
        top: elementRect.top - documentRect.top,
        right: elementRect.right - documentRect.left,
        bottom: elementRect.bottom - documentRect.top,
        left: elementRect.left - documentRect.left,
        width: elementRect.width,
        height: elementRect.height
      };
    }

    return {
      restrict: 'A',
      scope: {
        user: '=kfUserTooltip',
        library: '=',
        desc: '@'
      },
      link: function (scope, element) {
        var tooltip;
        var timeout;
        var touchedAt;

        scope.tipping = false;
        scope.below = false;

        element.on('mouseenter', function (e) {
          // For some reason, there is no way to compute the tooltip height on the fly.
          // This works because the tooltip heights never change.
          var tooltipHeight = scope.library ? 280 : 100;

          // These values are relative to the full page, so scrolling won't change behavior.
          // Use the standard element.getBoundingClientRect() if you want to render the tooltip
          // above or below depending on the scroll position.
          var elementRect = getAbsoluteBoundingRect(element);

          scope.below = (elementRect.top - tooltipHeight <= 0);

          if (Date.now() - touchedAt < 1000 || angular.element(e.target).is('.kf-utt,.kf-utt *')) {
            return;
          }
          if (!tooltip) {
            tooltip = angular.element($templateCache.get(scope.library ?
              'common/directives/userTooltip/userLibTooltip.tpl.html' :
              'common/directives/userTooltip/userTooltip.tpl.html'));
            element.append(tooltip);
            $compile(tooltip)(scope);
          }
          timeout = $timeout(function () {
            scope.tipping = null;
            ready.then(function () {
              scope.tipping = scope.tipping === null;
            });
          }, 50);
          var ready = scope.library ? libraryService.getLibraryInfoById(scope.library.id).then(function (data) {
            scope.library = data.library;
            scope.library.path = data.library.path || data.library.url;
          }) : timeout;
        }).on('touchstart touchend', function () {
          touchedAt = Date.now();
        }).on('mouseleave mousedown', function (e) {
          if (e.type !== 'mousedown' || !angular.element(e.target).is('.kf-ult,.kf-ult *')) {
            $timeout.cancel(timeout);
            scope.$apply(function () {
              scope.tipping = false;
            });
          }
        });

        scope.$on('$destroy', function () {
          $timeout.cancel(timeout);
          scope.tipping = false;
          if (tooltip) {
            tooltip.remove();
          }
        });
      }
    };
  }
]);
