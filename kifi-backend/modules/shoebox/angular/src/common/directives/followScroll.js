'use strict';

angular.module('kifi')

.directive('kfFollowScroll', [
  '$window', '$timeout',
  function ($window, $timeout) {

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

    var $body = angular.element('body');
    var desktopMq = $window.matchMedia('(min-width: 480px)');

    return {
      restrict: 'A',
      link: function ($scope, element, attrs) {
        function moveFloatMenu() {
          var pageScroll = $body.scrollTop();
          var headerOffset = $header.height() + 8;
          var offset = pageScroll - positionY + headerOffset;

          if (offset < 0 || pageScroll + headerOffset < positionY) {
            element.css({
              'position': '',
              'top': ''
            });
          } else {
            element.css({
              'position': 'fixed',
              'top': headerOffset + 'px'
            });
          }
        }

        function updateMq() {
          if (desktopMq.matches) {
            $window.addEventListener('scroll', moveFloatMenu);
            moveFloatMenu();
          } else {
            $window.removeEventListener('scroll', moveFloatMenu);
            element.css({
              'position': '',
              'top': ''
            });
          }
        }

        var $header = (attrs.kfFollowScrollHeader && angular.element(attrs.kfFollowScrollHeader)) || angular.element('.kf-lih');
        var positionY;

        // Wait for the page to fully render before calculating the position.
        $timeout(function () {
          positionY = getAbsoluteBoundingRect(element).top;

          desktopMq.addListener('change', updateMq);
          updateMq();

          $scope.$on('$destroy', function () {
            $window.removeEventListener('scroll', moveFloatMenu);
            desktopMq.removeListener('change', updateMq);
          });
        });
      }
    };
  }
]);
