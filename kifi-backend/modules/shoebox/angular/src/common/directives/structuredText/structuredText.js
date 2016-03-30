'use strict';

angular.module('kifi')

.directive('kfStructuredText', [
  'extensionLiaison', 'RecursionHelper',
  function (extensionLiaison, RecursionHelper) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/structuredText/structuredText.tpl.html',
      scope: {
        segments: '='
      },
      compile: function (element) {
        return RecursionHelper.compile(element);
      }
    };
  }
]);
