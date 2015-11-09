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
        onSave: '=',
        linkable: '=',
        readonly: '='
      },
      replace: true,
      link: function ($scope, $element) {
        $scope.view = {
          editableValue: $scope.value,
          inputPlaceholder: $scope.inputPlaceholder,
          textareaHeight: '',
          textareaWidth: ''
        };
        $scope.focus = false;
        $scope.saveable = true;

        $scope.editEvent = function($event) {
          if ($event.which === 27) {
            $scope.cancel();
          }
          else if ($event.which === 13) {
            $event.preventDefault();
            $scope.save();
          }
        };

        $scope.cancel = function () {
          $scope.view.editableValue = $scope.value;
          $scope.disableEditor();
        };

        // Called only when blurring without save
        $scope.disableEditor = function() {
          $scope.saveable = false;
          $element.find('textarea')[0].blur();
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
          $scope.focus = false;

          if ($scope.saveable) {
            $scope.save();
            $scope.saveable = true;
          }
        };

        $scope.onFocus = function () {
          $scope.selectAll();
          $scope.focus = true;
        };

        $scope.selectAll = function () {
          var textarea = $element.find('textarea')[0];
          if (!$scope.focus && textarea.selectionStart === textarea.selectionEnd) {
            textarea.select();
          }
        };

        function calculateHeight() {
          var textarea = $element.find('textarea');
          var span = $element.find('span');
          if (textarea && span) {
            // Show an element that matches the textarea's styling to determine
            // how high it should be to show all of textarea's text.
            var tester = span[0];
            tester.style.width = textarea.css('width');
            $scope.view.textareaHeight = {'height': span.css('height') };
          }
        }

        $scope.$watch('view.editableValue', function () {
          calculateHeight();
        }, true);
      }
    };
  }
]);
