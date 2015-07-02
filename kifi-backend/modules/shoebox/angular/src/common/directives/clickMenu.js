'use strict';

angular.module('kifi')

.directive('kfClickMenu', [
  '$window',
  function ($window) {
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

        element.on('click', function (clickedInEvent) {

          function handleCloseClick(clickedOutEvent) {
            // When the user clicks anywhere after opening the dropdown,
            // close it if the click is not on the dropdown.
            if (clickedOutEvent.target !== clickedInEvent.target) {
              // Stop listening for close-clicks
              $window.document.removeEventListener('click', handleCloseClick);
              hideMenu();
            }
          }

          if (!isOpen() && clickedInEvent.which === 1) {
            // Listen for close-clicks
            $window.document.addEventListener('click', handleCloseClick);
            showMenu();
          }
        });
      }
    };
  }
]);
