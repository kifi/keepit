'use strict';

angular.module('kifi.profileNameInput', ['keyIndices', 'util'])

.directive('kfProfileNameInput', ['keyIndices', 'util',
  function (keyIndices, util) {
    return {
      restrict: 'A',
      scope: {
        name: '=',
        validateName: '&',
        saveName: '&',
        explicitEnabling: '='
      },
      templateUrl: 'profile/profileNameInput.tpl.html',
      link: function (scope, element/*, attrs*/) {
        // Scope data.
        scope.badFirstName = false;
        scope.badLastName = false;
        scope.errorHeader = '';
        scope.errorBody = '';
        scope.editing = false;

        // Internal data.
        var oldName = {};

        // Key press event listeners.
        element.find('input')
          .on('keydown', function (event) {
            switch (event.which) {
              case keyIndices.KEY_ESC:
                clearInputError();
                revertToOldName();
                scope.editing = false;
                break;
              case keyIndices.KEY_ENTER:
                scope.save();
                break;
            }
          });

        // Internal methods.
        var showInputError = function (errorType, error) {
          if (errorType === 'badFirstName') {
            scope.badFirstName = true;
          } else if (errorType === 'badLastName') {
            scope.badLastName = true;
          }
          scope.errorHeader = error.header || '';
          scope.errorBody = error.body || '';
        };

        var clearInputError = function () {
          scope.badFirstName = false;
          scope.badLastName = false;
        };

        var saveOldName = function () {
          oldName = {
            firstName: scope.name.firstName,
            lastName: scope.name.lastName
          };
        };

        var revertToOldName = function () {
          scope.name = {
            firstName: oldName.firstName,
            lastName: oldName.lastName
          };
        };

        var sameName = function (nameA, nameB) {
          return (nameA.firstName === nameB.firstName) && (nameA.lastName === nameB.lastName);
        };

        // Scope methods.
        scope.edit = function () {
          clearInputError();
          saveOldName();
          scope.editing = true;
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
