'use strict';

angular.module('util', [])

.value('util', {
  startsWith: function (str, prefix) {
    return str === prefix || str.lastIndexOf(prefix, 0) === 0;
  },
  endsWith: function (str, suffix) {
    return str === suffix || str.indexOf(suffix, str.length - suffix.length) !== -1;
  }
})

.directive('postRepeatDirective', [
  '$timeout', '$window',
  function ($timeout, $window) {
    return function (scope) {
      if (scope.$first) {
        if ($window.console && $window.console.time) {
          $window.console.time('postRepeatDirective');
        }
      }

      if (scope.$last) {
        $timeout(function () {
          if ($window.console && $window.console.time) {
            $window.console.time('postRepeatDirective');
            $window.console.timeEnd('postRepeatDirective');
          }
        });
      }
    };
  }
]);
