'use strict';

angular.module('kifi')

.directive('kfProfileInput', [
  '$timeout', '$q', 'keyIndices', 'util',
  function ($timeout, $q, keyIndices, util) {
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
          })
          .on('focus', function () {
            $timeout(function () { setEditState(); });
          });

        function cancelCancelEdit() {
          if (cancelEditPromise) {
            $timeout.cancel(cancelEditPromise);
            cancelEditPromise = null;
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
          cancelCancelEdit();
          scope.state.editing = true;
          scope.state.invalid = false;
        }

        scope.edit = function () {
          scope.state.currentValue = scope.state.value;
          setEditState();
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
