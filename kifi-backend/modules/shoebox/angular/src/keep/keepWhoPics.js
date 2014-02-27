'use strict';

angular.module('kifi.keepWhoPics', ['kifi.keepWhoService'])

.directive('kfKeepWhoPics', [
  'keepWhoService',
  function (keepWhoService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'keep/keepWhoPics.tpl.html',
      scope: {
        me: '=',
        keepers: '='
      },
      link: function(scope) {
        scope.getPicUrl = keepWhoService.getPicUrl;
      }
    };
  }
]);
