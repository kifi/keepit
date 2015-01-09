'use strict';

angular.module('kifi')

.directive('kfKeepWhoText', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoText.tpl.html',
      scope: {
        keep: '='
      }
    };
  }
]);
