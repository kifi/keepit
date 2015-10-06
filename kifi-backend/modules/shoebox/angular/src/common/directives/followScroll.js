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
          var offset = pageScroll - positionY + ($header.height() + 3);

          if (offset < 0 || pageScroll + 58 < positionY) {
            offset = 0;
          }

          element.animate(
            {
              borderSpacing: offset
            },
            {
              step: function (now) {
                this.style.transform = 'translateY(' + now + 'px)';
              },
              duration: 500,
              queue: false
            }
          );
        }

        function updateMq() {
          if (desktopMq.matches) {
            $window.addEventListener('scroll', _moveFloatMenu);
            moveFloatMenu();
          } else {
            $window.removeEventListener('scroll', _moveFloatMenu);
            element[0].style.transform = null;
            element[0].style.borderSpacing = null;
          }
        }

        var $header = (attrs.kfFollowScrollHeader && angular.element(attrs.kfFollowScrollHeader)) || angular.element('.kf-lih');
        var _moveFloatMenu = _.debounce(moveFloatMenu, 150);
        var positionY;

        // Wait for the page to fully render before calculating the position.
        $timeout(function () {
          positionY = getAbsoluteBoundingRect(element).top;

          desktopMq.addEventListener('change', updateMq);
          updateMq();

          $scope.$on('$destroy', function () {
            $window.removeEventListener('scroll', _moveFloatMenu);
            desktopMq.removeEventListener('change', updateMq);
          });
        });
      }
    };
  }
]);
