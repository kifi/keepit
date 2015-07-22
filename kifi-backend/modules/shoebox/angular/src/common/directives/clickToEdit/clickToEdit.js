'use strict';

angular.module('kifi')

.directive('kfClickToEdit', [
  '$document', '$timeout',
  function ($document, $timeout) {
    return {
      templateUrl: 'common/directives/clickToEdit/clickToEdit.tpl.html',
      scope: {
        value: '=',
        onSave: '='
      },
      replace: true,
      link: function ($scope, $element) {
        $scope.view = {
          editableValue: $scope.value,
          editorEnabled: false
        };

        $scope.enableEditor = function() {
          $scope.view.editorEnabled = true;
          $scope.view.editableValue = $scope.value;
          $timeout(function () {
            $element.find('input').focus();
          });
        };

        $scope.disableEditor = function() {
          $scope.view.editorEnabled = false;
        };

        $scope.save = function () {
          $scope.value = $scope.view.editableValue;
          if ($scope.onSave) {
            $scope.onSave($scope.value);
          }
          $scope.disableEditor();
        };

        $scope.cancel = function () {
          $scope.view.editableValue = $scope.value;
          $scope.disableEditor();
        };

        $scope.onBlur = function (e) {
          // if ($element.has($document.targetElement).length === 0) {
          //   $scope.cancel();
          // } else {
            $scope.save();
          // }
        };
      }
    };
  }
]);
