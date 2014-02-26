'use strict';

angular.module('util', [])

.value('util', {
  startsWith: function (str, prefix) {
    return str === prefix || str.lastIndexOf(prefix, 0) === 0;
  },
  endsWith: function (str, suffix) {
    return str === suffix || str.indexOf(suffix, str.length - suffix.length) !== -1;
  }
});
