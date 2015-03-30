'use strict';

angular.module('kifi')

.directive('kfHoverMenu', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      link: function (scope, element) {
        var timeout, openedAt;

        function showMenu() {
          $timeout.cancel(timeout);
          element.addClass('kf-open');
          openedAt = Date.now();
        }

        function hideMenu() {
          element.removeClass('kf-open');
          openedAt = null;
        }

        element.on('mouseenter', function () {
          $timeout.cancel(timeout);
          timeout = $timeout(showMenu, 120);
        });

        element.on('mouseleave', function () {
          $timeout.cancel(timeout);
          hideMenu();
        });

        element.on('click', function (event) {
          $timeout.cancel(timeout);
          if (openedAt) {
            if (Date.now() - openedAt > 500 || angular.element(event.target).is('a[href],a[href] *,menu *')) {
              hideMenu();
            }
          } else {
            showMenu();
          }
        });
      }
    };
  }
]);
