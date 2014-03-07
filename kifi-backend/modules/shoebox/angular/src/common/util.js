'use strict';

angular.module('util', [])

.value('util', {
  startsWith: function (str, prefix) {
    return str === prefix || str.lastIndexOf(prefix, 0) === 0;
  },
  endsWith: function (str, suffix) {
    return str === suffix || str.indexOf(suffix, str.length - suffix.length) !== -1;
  },
  trimInput: function (input) {
    return input ? input.trim().replace(/\s+/g, ' ') : '';
  },
  validateEmail: function (input) {
    var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;
    return emailAddrRe.test(input);
  },
  replaceArrayInPlace: function (oldArray, newArray) {
    // empties oldArray, loads newArray values into it, keeping the same reference.
    oldArray = oldArray || [];
    oldArray.length = 0;
    _.each(newArray, function (elem) {
      oldArray.push(elem);
    });
  },
  replaceObjectInPlace: function (oldObj, newObj) {
    // empties oldObj, loads newObj key/values into it, keeping the same reference.
    _.forOwn(oldObj || {}, function (num, key) {
      delete oldObj[key];
    });
    _.forOwn(newObj || {}, function (num, key) {
      oldObj[key] = newObj[key];
    });
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
