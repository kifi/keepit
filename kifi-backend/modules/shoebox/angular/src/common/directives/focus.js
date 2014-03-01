'use strict';

angular.module('kifi.focus', [])

.directive('focusWhen', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      scope: {
        focusWhen: '='
      },
      link: function (scope, element /*, attrs*/ ) {

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
  function() {
    return {
      restrict: 'A',
      scope: {
        focusCond: '='
      },
      link: function(scope, element) {
        scope.$watch('focusCond', function(val) {
          if (val) {
            element.focus();
          }
        });
      }
    }
  }
])

.directive('withFocus', [
  function () {
    return {
      restrict: 'A',
      scope: {
        withFocus: '&'
      },
      link: function (scope, element /*, attrs*/ ) {
        if (scope.withFocus()) {
          element.focus();
        }
      }
    };
  }
]);
