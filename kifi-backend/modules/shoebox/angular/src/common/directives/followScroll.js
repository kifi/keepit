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

    var bodyContainer = angular.element('#kf-body-container-content')[0];
    var documentElement = $window.document.documentElement;
    var desktopMq = $window.matchMedia(bodyContainer ? '(min-width: 710px)' : '(min-width: 480px)');

    return {
      restrict: 'A',
      link: function ($scope, element, attrs) {
        function moveFloatMenu() {
          var pageScroll = bodyContainer ? bodyContainer.scrollTop : -documentElement.getBoundingClientRect().top;
          var headerOffset = (bodyContainer ? bodyContainer.getBoundingClientRect().top : $header.height()) + 8;
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
            (bodyContainer || $window).addEventListener('scroll', moveFloatMenu);
            moveFloatMenu();
          } else {
            (bodyContainer || $window).removeEventListener('scroll', moveFloatMenu);
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

          desktopMq.addListener(updateMq);
          updateMq();

          $scope.$on('$destroy', function () {
            (bodyContainer || $window).removeEventListener('scroll', moveFloatMenu);
            desktopMq.removeListener(updateMq);
          });
        });
      }
    };
  }
]);
