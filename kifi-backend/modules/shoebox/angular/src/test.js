'use strict';

angular.module('kifi')

.controller('TestCtrl', [
  '$scope', 'signupService',
  function ($scope, signupService) {

    $scope.register = signupService.register;
  }
]);
