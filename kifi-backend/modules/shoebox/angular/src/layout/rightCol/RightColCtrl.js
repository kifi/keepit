'use strict';

angular.module('kifi.layout.rightCol', [])

.controller('RightColCtrl', [
  '$scope', '$window',
  function ($scope, $window) {
    $window.console.log('RightColCtrl');
  }
]);

