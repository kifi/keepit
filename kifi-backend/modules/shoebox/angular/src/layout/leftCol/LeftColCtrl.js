'use strict';

angular.module('kifi.layout.leftCol', [])

.controller('LeftColCtrl', [
  '$scope', '$element', '$window', '$timeout',
  function ($scope, $element, $window, $timeout) {
    $window.console.log('LeftColCtrl');
    $scope.height = '100%';

    var updateHeight = _.throttle(function () {
      $element.css('height', $window.innerHeight + 'px');
    }, 100);

    angular.element($window).resize(updateHeight);

    $timeout(updateHeight);
  }
]);

