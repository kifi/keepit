'use strict';

angular.module('kifi')

.directive('kfGenericBanner', [
  function () {
    return {
      templateUrl: 'common/directives/genericBanner/genericBanner.tpl.html',
      scope: {
        text: '@',
        actions: '='
      },
      replace: true,
      link: function () {

      }
    };
  }
]);
