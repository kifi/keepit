'use strict';

angular.module('kifi.layout.main', [])

.controller('MainCtrl', [
  '$scope',
  function ($scope) {
    var KEY_ESC = 27;

    $scope.search = {};

    $scope.isEmpty = function () {
      return !$scope.search.text;
    };

    $scope.onKeydown = function (e) {
      if (e.keyCode === KEY_ESC) {
        $scope.clear();
      }
    };

    $scope.onFocus = function () {
      $scope.focus = true;
    };

    $scope.onBlur = function () {
      $scope.focus = false;
    };

    $scope.clear = function () {
      $scope.search.text = '';
    };

    $scope.undoAction = {
      message: 'hi'
    };

    $scope.undo = function () {
      $scope.undoAction = null;
    };
  }
]);
