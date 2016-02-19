'use strict';

angular.module('kifi')

.directive('kfFollowScroll', [
  '$window', '$timeout',
  function ($window, $timeout) {
    var bodyContainer = angular.element('#kf-body-container-content')[0] || $window.document.documentElement;
    var documentElement = $window.document.documentElement;
    var desktopMq = $window.matchMedia(bodyContainer ? '(min-width: 710px)' : '(min-width: 480px)');

    function getAbsoluteBoundingRect(element) {
      var documentRect = documentElement.getBoundingClientRect();
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
      link: function ($scope, element, attrs) {
        var $header = (attrs.kfFollowScrollHeader && angular.element(attrs.kfFollowScrollHeader)) || angular.element('.kf-lih');
        var positionY;

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

        // Wait for the page to fully render before calculating the position.
        $timeout(function () {
          var elementRect = getAbsoluteBoundingRect(element);
          positionY = (bodyContainer ? elementRect.top + bodyContainer.scrollTop : elementRect.top);

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
