'use strict';

angular.module('kifi.layout.sidebar', ['kifi.layoutService'])

.directive('kfSidebar', [
  'layoutService',
  function (layoutService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'layout/sidebar/sidebar.tpl.html',
      scope: {},
      link: function (scope, element) {
        scope.$watch(layoutService.sidebarActive, function () {
          if (layoutService.sidebarActive()) {
            element.addClass('kf-sidebar-active');
          } else {
            element.removeClass('kf-sidebar-active');
          }
        });
      }
    };
  }
]);
