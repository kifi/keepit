'use strict';

angular.module('kifi.profileInput', ['util', 'kifi.profileService'])

.directive('kfProfileInput', [
  '$document', '$timeout', '$q', 'keyIndices', 'util',
  function ($document, $timeout, $q, keyIndices, util) {
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
              case keyIndices.KEY_ESC:
                cancel();
                break;
              case keyIndices.KEY_ENTER:
                scope.save();
                break;
            }
          })
          .on('focus', function () {
            $timeout(function () { setEditState(); });
          });

        element.find('.profile-input-save')
          .on('blur', function () {
            scope.$apply(cancel);
          });

        // Internal methods.
        function cancel () {
          scope.state.value = scope.state.currentValue;
          scope.state.editing = false;
        }

        function onClickOutsideInput(event) {
          // When user clicks outside the input and save button, cancel edits.
          if (!event.target.classList.contains('profile-email-input') &&
            !event.target.classList.contains('profile-input-save')) {
            $document.off('mousedown', onClickOutsideInput);
            scope.$apply(cancel);
          }
        }

        function updateValue (value) {
          scope.state.value = scope.state.currentValue = value;
        }

        function setInvalid (error) {
          scope.state.invalid = true;
          scope.errorHeader = error.header || '';
          scope.errorBody = error.body || '';
        }

        function setEditState () {
          scope.state.editing = true;
          scope.state.invalid = false;
          $document.on('mousedown', onClickOutsideInput);
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
