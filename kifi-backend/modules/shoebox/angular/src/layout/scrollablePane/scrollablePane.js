'use strict';

angular.module('kifi.layout.scrollablePane', ['kifi.layoutService'])

.directive('kfScrollablePane', [
  'layoutService',
  function (layoutService) {
    return {
      restrict: 'A',
      scope: {},
      link: function (scope, element) {
        scope.$watch(layoutService.sidebarActive, function () {
          if (layoutService.sidebarActive()) {
            element.addClass('kf-srollable-pane-pushed-right');
          } else {
            element.removeClass('kf-srollable-pane-pushed-right');
          }
        });
      }
    };
  }
]);
