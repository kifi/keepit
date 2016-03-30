'use strict';

angular.module('kifi')

.directive('kfActivityEventSegments', [
  'RecursionHelper',
  function (RecursionHelper) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepActivity/activityEvent/activityEventSegments.tpl.html',
      scope: {
        segments: '=kfActivityEventSegments'
      },
      compile: function (element) {
        return RecursionHelper.compile(element);
      }
    };
  }
]);
