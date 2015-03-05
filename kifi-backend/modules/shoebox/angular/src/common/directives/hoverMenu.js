'use strict';

angular.module('kifi')

.directive('kfHoverMenu', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      link: function (scope, element) {
        var timeout;

        function showMenu() {
          element.addClass('kf-open');
        }

        function hideMenu() {
          element.removeClass('kf-open');
        }

        element.on('mouseenter', function () {
          $timeout.cancel(timeout);
          timeout = $timeout(showMenu, 120);
        });

        element.on('mouseleave click', function () {
          $timeout.cancel(timeout);
          hideMenu();
        });
      }
    };
  }
]);
