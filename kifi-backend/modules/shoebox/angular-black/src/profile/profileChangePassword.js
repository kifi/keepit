'use strict';

angular.module('kifi.profileChangePassword', ['util', 'kifi.profileService'])

.directive('kfProfileChangePassword', [
  'profileService', 'keyIndices',
  function (profileService, keyIndices) {
    return {
      restrict: 'A',
      scope: {},
      templateUrl: 'profile/profileChangePassword.tpl.html',
      link: function (scope, element) {
        scope.isOpen = false;
        scope.inputs = {oldPassword: '', newPassword1: '', newPassword2: ''};

        scope.toggle = function () {
          scope.isOpen = !scope.isOpen;
          if (scope.isOpen) {
            scope.successMessage = '';
          }
        };

        element.find('input').on('keydown', function (e) {
          switch (e.which) {
            case keyIndices.KEY_ESC:
              this.blur();
              break;
            case keyIndices.KEY_ENTER:
              scope.$apply(scope.updatePassword);
              break;
          }
        });

        scope.updatePassword = function () {
          scope.successMessage = '';
          if (scope.inputs.oldPassword.length < 7) {
            scope.errorMessage = 'Your current password is not correct.';
          } else if (scope.inputs.newPassword1 !== scope.inputs.newPassword2) {
            scope.errorMessage = 'Your new passwords do not match.';
          } else if (scope.inputs.newPassword1.length < 7) {
            scope.errorMessage = 'Your password needs to be longer than 7 characters.';
          } else if (scope.inputs.oldPassword === scope.inputs.newPassword1) {
            scope.errorMessage = 'Your new password needs to be different from your current one.';
          } else {
            scope.errorMessage = '';
            profileService.sendChangePassword(scope.inputs.oldPassword, scope.inputs.newPassword1)
              .then(function () {
                scope.successMessage = 'Your password was successfully updated!';
                scope.inputs = {};
                scope.toggle();
              }, function (result) {
                if (result.data.error === 'bad_old_password') {
                  scope.errorMessage = 'Your current password is not correct.';
                } else if (result.data.error === 'bad_new_password') {
                  scope.errorMessage = 'Your password needs to be longer than 7 characters.';
                } else {
                  scope.errorMessage = 'An error occured. Try again?';
                }
              });
          }
        };
      }
    };
  }
]);
