'use strict';

angular.module('kifi')

.directive('kfUserTooltip', [
  '$timeout', '$compile', '$templateCache', 'libraryService',
  function ($timeout, $compile, $templateCache, libraryService) {
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

        element.on('mouseenter', function (e) {
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
            scope.library.path = data.library.url;
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
