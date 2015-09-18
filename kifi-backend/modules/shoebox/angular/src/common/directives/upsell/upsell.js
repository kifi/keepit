'use strict';

angular.module('kifi')

.directive('kfUpsell', [
  function () {
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
      }
    };
  }
]);
