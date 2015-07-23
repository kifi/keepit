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
          ($event.which === 27 && $scope.cancel()) || ($event.which === 13 && $scope.save());
        }

        $scope.enableEditor = function() {
          $scope.view.editableValue = $scope.value;
          $timeout(function () {
            $element.find('input').focus();
          });
        };

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
          $scope.value = $scope.view.editableValue;

          if ($scope.onSave) {
            $timeout(function() {
              $scope.onSave();
            });
          }
          $scope.disableEditor();
          // TODO (Adam): Should validate.
          // Success: sets last value to current one, shows success.
          // Error: Sets current value to last one, shows error.
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
