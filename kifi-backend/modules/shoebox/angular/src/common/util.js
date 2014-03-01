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
])

.constant('keyIndices', {
  KEY_UP: 38,
  KEY_DOWN: 40,
  KEY_ENTER: 13,
  KEY_ESC: 27,
  KEY_TAB: 9,
  KEY_DEL: 46,
  KEY_F2: 113
});
