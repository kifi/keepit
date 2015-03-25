'use strict';

angular.module('kifi')

.directive('kfProfileNameInput', ['$document', '$window', 'keyIndices', 'util',
  function ($document, $window, keyIndices, util) {
    return {
      restrict: 'A',
      scope: {
        name: '=',
        validateName: '&',
        saveName: '&',
        explicitEnabling: '='
      },
      templateUrl: 'profile/profileNameInput.tpl.html',
      link: function (scope, element) {
        // Scope data.
        scope.badFirstName = false;
        scope.badLastName = false;
        scope.errorHeader = '';
        scope.errorBody = '';
        scope.editing = false;

        // Internal data.
        var oldName = {};

        // DOM event listeners.
        element.find('input')
          .on('keydown', function (event) {
            switch (event.which) {
              case keyIndices.KEY_ESC:
                cancel();
                break;
              case keyIndices.KEY_ENTER:
                scope.save();
                break;
            }
          });

        // Internal methods.
        function cancel() {
          clearInputError();
          revertToOldName();
          scope.editing = false;
        }

        function onClickOutsideInput(event) {
          if (!angular.element(event.target).is('.profile-name-input, .profile-input-save')) {
            $document.off('mousedown', onClickOutsideInput);
            scope.$evalAsync(cancel);
          }
        }

        function onFocusOutsideInput(event) {
          if (!angular.element(event.target).is('.profile-name-input, .profile-input-save')) {
            $window.removeEventListener('focus', onFocusOutsideInput, true);
            scope.$evalAsync(cancel);
          }
        }

        function showInputError(errorType, error) {
          if (errorType === 'badFirstName') {
            scope.badFirstName = true;
          } else if (errorType === 'badLastName') {
            scope.badLastName = true;
          }
          scope.errorHeader = error.header || '';
          scope.errorBody = error.body || '';
        }

        function clearInputError() {
          scope.badFirstName = false;
          scope.badLastName = false;
        }

        function saveOldName() {
          oldName = {
            firstName: scope.name.firstName,
            lastName: scope.name.lastName
          };
        }

        function revertToOldName() {
          scope.name = {
            firstName: oldName.firstName,
            lastName: oldName.lastName
          };
        }

        function sameName(nameA, nameB) {
          return (nameA.firstName === nameB.firstName) && (nameA.lastName === nameB.lastName);
        }

        // Scope methods.
        scope.edit = function () {
          clearInputError();
          saveOldName();
          scope.editing = true;
          $document.on('mousedown', onClickOutsideInput);
          $window.addEventListener('focus', onFocusOutsideInput, true);
        };

        scope.save = function () {
          clearInputError();

          var nameToSave = {
            firstName: util.trimInput(scope.name.firstName),
            lastName: util.trimInput(scope.name.lastName)
          };

          // Validate name.
          var validationResult = scope.validateName({name: nameToSave.firstName});
          if (validationResult && !validationResult.isSuccess && validationResult.error) {
            showInputError('badFirstName', validationResult.error);
            return;
          }
          validationResult = scope.validateName({name: nameToSave.lastName});
          if (validationResult && !validationResult.isSuccess && validationResult.error) {
            showInputError('badLastName', validationResult.error);
            return;
          }

          scope.editing = false;
          $document.off('mousedown', onClickOutsideInput);
          $window.removeEventListener('focus', onFocusOutsideInput, true);

          // Save name.
          if (sameName(nameToSave, oldName)) {
            return;
          }
          scope.saveName({name: nameToSave});
        };
      }
    };
  }
]);
