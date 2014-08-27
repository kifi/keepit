'use strict';

angular.module('kifi')
  .filter('titlecase', function () {
    return function(input) {
      return input.slice(0, 1).toUpperCase() + input.slice(1).toLowerCase();
    };
  });
