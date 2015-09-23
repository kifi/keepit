'use strict';

angular.module('kifi')

.directive('kfUpsell', [
  'profileService',
  function (profileService) {
    return {
      restrict: 'A',
      transclude: true,
      replace: true,
      templateUrl: 'common/directives/upsell/upsell.tpl.html',
      scope: {
        position: '@',
        icon: '@',
        onHover: '&',
        onClick: '&'
      },
      link: function ($scope, element) {
        if (profileService.me.experiments.indexOf('admin') === -1) {
          element.remove();
        }
      }
    };
  }
]);
