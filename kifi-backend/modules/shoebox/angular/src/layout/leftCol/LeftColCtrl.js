'use strict';

angular.module('kifi.layout.leftCol', [])

.controller('LeftColCtrl', [
  '$scope', '$window',
  function ($scope, $window) {
    $window.console.log('LeftColCtrl');
  }
]);

