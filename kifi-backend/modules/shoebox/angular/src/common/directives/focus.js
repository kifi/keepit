'use strict';

angular.module('kifi')

.directive('focusWhen', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      scope: {
        focusWhen: '='
      },
      link: function (scope, element) {

        function focus() {
          element.focus();
          scope.focusWhen = false;
        }

        scope.$watch('focusWhen', function (val) {
          if (val) {
            $timeout(focus);
          }
        });
      }
    };
  }
])

.directive('kfFocusIf', [
  function () {
    return {
      restrict: 'A',
      scope: {
        focusCond: '='
      },
      link: function (scope, element) {
        scope.$watch('focusCond', function (val) {
          if (val) {
            element.focus();
          }
        });
      }
    };
  }
])

.directive('kfAutofocus', [
  function () {
    return {
      restrict: 'A',
      link: function (scope, element) {
        element.focus();
      }
    };
  }
]);
