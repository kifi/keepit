'use strict';

angular.module('kifi')

.directive('kfClickToEdit', [
  '$document', '$timeout',
  function ($document, $timeout) {
    return {
      templateUrl: 'common/directives/clickToEdit/clickToEdit.tpl.html',
      scope: {
        value: '=',
        inputPlaceholder: '=',
        textarea: '=',
        onSave: '='
      },
      replace: true,
      link: function ($scope, $element) {
        $scope.view = {
          editableValue: $scope.value,
          inputPlaceholder: $scope.inputPlaceholder
        };
        $scope.saveable = true;

        $scope.editEvent = function($event) {
          if ($event.which === 27) {
            $scope.cancel();
          }
          else if ($event.which === 13) {
            $scope.save();
          }
        }

        $scope.cancel = function () {
          $scope.view.editableValue = $scope.value;
          $scope.disableEditor();
        };

        // Called only when blurring without save
        $scope.disableEditor = function() {
          $scope.saveable = false;
          $element.find('input')[0].blur();
        };

        $scope.save = function () {
          if ($scope.onSave && $scope.value !== $scope.view.editableValue) {
            $scope.value = $scope.view.editableValue;

            $timeout(function() {
              $scope.onSave();
            });
          }
          $scope.disableEditor();
        };

        $scope.onBlur = function () {
          if ($scope.saveable) {
            $scope.save();
            $scope.saveable = true;
          }
        };
      }
    };
  }
]);
