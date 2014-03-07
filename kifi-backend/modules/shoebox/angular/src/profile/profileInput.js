'use strict';

angular.module('kifi.profileInput', ['util', 'kifi.profileService'])

.directive('kfProfileInput', [
  '$timeout', '$q', 'keyIndices', 'util',
  function ($timeout, $q, keyIndices, util) {
    return {
      restrict: 'A',
      scope: {
        state: '=inputState',
        validateAction: '&inputValidateAction',
        saveAction: '&inputSaveAction'
      },
      transclude: true,
      templateUrl: 'profile/profileInput.tpl.html',
      link: function (scope, element) {
        scope.state.editing = scope.state.invalid = false;

        var cancelEditPromise;

        element.find('input')
          .on('keydown', function (e) {
            switch (e.which) {
            case keyIndices.KEY_ESC:
              scope.$apply(scope.cancel);
              break;
            case keyIndices.KEY_ENTER:
              scope.$apply(scope.save);
              break;
            }
          })
          .on('blur', function () {
            // give enough time for save() to fire. todo(martin): find a more reliable solution
            cancelEditPromise = $timeout(scope.cancel, 100);
          });

        function cancelCancelEdit() {
          if (cancelEditPromise) {
            cancelEditPromise = null;
            $timeout.cancel(cancelEditPromise);
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

        scope.edit = function () {
          cancelCancelEdit();
          scope.state.currentValue = scope.state.value;
          scope.state.editing = true;
        };

        scope.cancel = function () {
          scope.state.value = scope.state.currentValue;
          scope.state.editing = false;
        };

        scope.save = function () {
          // Validate input
          var value = util.trimInput(scope.state.value);
          var validationResult = scope.validateAction({value: value});
          if (validationResult && !validationResult.isSuccess && validationResult.error) {
            setInvalid(validationResult.error);
            return;
          }
          scope.state.invalid = false;
          scope.state.prevValue = scope.state.currentValue;
          updateValue(value);
          scope.state.editing = false;

          // Save input
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
