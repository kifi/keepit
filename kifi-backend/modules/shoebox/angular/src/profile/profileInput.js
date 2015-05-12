'use strict';

angular.module('kifi')

.directive('kfProfileInput', [
  '$document', '$q','$timeout', '$window', 'KEY', 'util',
  function ($document, $q, $timeout, $window, KEY, util) {
    return {
      restrict: 'A',
      scope: {
        state: '=inputState',
        validateAction: '&inputValidateAction',
        saveAction: '&inputSaveAction',
        explicitEnabling: '=',
        actionLabel: '@'
      },
      transclude: true,
      templateUrl: 'profile/profileInput.tpl.html',
      link: function (scope, element) {
        // Scope data.
        scope.state.editing = scope.state.invalid = false;

        // DOM event listeners.
        element.find('input')
          .on('keydown', function (e) {
            switch (e.which) {
              case KEY.ESC:
                cancel();
                break;
              case KEY.ENTER:
                scope.save();
                break;
            }
          })
          .on('focus', function () {
            $timeout(function () { setEditState(); });
          });

        // Internal methods.
        function cancel() {
          scope.state.value = scope.state.currentValue;
          scope.state.editing = false;
        }

        function onClickOutsideInput(event) {
          if (!angular.element(event.target).is('.profile-email-input, .profile-input-save')) {
            $document.off('mousedown', onClickOutsideInput);
            scope.$evalAsync(cancel);
          }
        }

        function onFocusOutsideInput(event) {
          if (!angular.element(event.target).is('.profile-email-input, .profile-input-save')) {
            $window.removeEventListener('focus', onFocusOutsideInput, true);
            scope.$evalAsync(cancel);
          }
        }

        function updateValue(value) {
          scope.state.value = scope.state.currentValue = value;
        }

        function setInvalid(error) {
          scope.state.invalid = true;
          scope.errorHeader = error.header || '';
          scope.errorBody = error.body || '';
        }

        function setEditState() {
          scope.state.editing = true;
          scope.state.invalid = false;
          $document.on('mousedown', onClickOutsideInput);
          $window.addEventListener('focus', onFocusOutsideInput, true);
        }

        // Scope methods.
        scope.edit = function () {
          scope.state.currentValue = scope.state.value;
          setEditState();
        };

        scope.save = function () {
          var value = util.trimInput(scope.state.value);

          // Validate input.
          var validationResult = scope.validateAction({value: value});
          if (validationResult && !validationResult.isSuccess && validationResult.error) {
            setInvalid(validationResult.error);
            return;
          }

          scope.state.prevValue = scope.state.currentValue;
          updateValue(value);
          scope.state.editing = false;
          $document.off('mousedown', onClickOutsideInput);
          $window.removeEventListener('focus', onFocusOutsideInput, true);

          if (value === scope.state.prevValue) {
            return;
          }

          // Save input.
          $q.when(scope.saveAction({value: value})).then(function (result) {
            if (result && !result.isSuccess) {
              if (result.error) {
                setInvalid(result.error);
              }
              updateValue(scope.state.prevValue);
            }
          });
        };
      }
    };
  }
]);
