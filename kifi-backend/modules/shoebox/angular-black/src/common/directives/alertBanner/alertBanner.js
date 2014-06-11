'use strict';

angular.module('kifi.alertBanner', [])


.directive('kfAlertBanner', [
  function () {
    return {
      scope: {
        'action': '=',
        'actionText': '@'
      },
      replace: true,
      restrict: 'A',
      transclude: true,
      templateUrl: 'common/directives/alertBanner/alertBanner.tpl.html',
      link: function (/*scope, element, attrs*/) {
      }
    };
  }
]);
