/* global jQuery: true */
'use strict';

angular.module('kifi')

.directive('kfClickMenu', [
  '$window',
  function ($window) {
    var $ = jQuery;

    return {
      restrict: 'A',
      link: function (scope, element) {

        function showMenu() {
          element.addClass('kf-open');
        }

        function hideMenu() {
          element.removeClass('kf-open');
        }

        function isOpen() {
          return element.hasClass('kf-open');
        }

        function handleMenuClick(e) {
          var $target;

          if (e.which === 1) {
            $target = $(e.target);
            // Open the menu if we clicked a child of the directive,
            // and that child is not a dropdown item,
            // and the directive's menu has items to show
            if ($target.closest(element).length &&
                !$target.hasClass('kf-dropdown-menu-item') &&
                element.find('menu').children().length) {
              // Only open the menu if it's closed
              if (!isOpen()) {
                showMenu();
              }
            } else {
              hideMenu();
            }
          }
        }

        element.on('$destroy', function () {
          $window.document.removeEventListener(handleMenuClick);
        });

        $window.document.addEventListener('click', handleMenuClick);
      }
    };
  }
]);
