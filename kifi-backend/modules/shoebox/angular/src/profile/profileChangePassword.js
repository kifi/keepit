'use strict';

angular.module('kifi')

.directive('kfProfileChangePassword', [
  'profileService', 'KEY',
  function (profileService, KEY) {
    return {
      restrict: 'A',
      scope: {},
      templateUrl: 'profile/profileChangePassword.tpl.html',
      link: function (scope, element) {
        scope.isOpen = false;
        scope.inputs = {oldPassword: '', newPassword1: '', newPassword2: ''};

        scope.prefs = profileService.prefs;
        scope.passwordAction = '';
        scope.hasNoPassword = false;

        scope.toggle = function () {
          scope.isOpen = !scope.isOpen;
          if (scope.isOpen) {
            scope.successMessage = '';
          }
        };

        element.find('input').on('keydown', function (e) {
          switch (e.which) {
            case KEY.ESC:
              this.blur();
              break;
            case KEY.ENTER:
              scope.$apply(scope.updatePassword);
              break;
          }
        });

        scope.updatePassword = function () {
          scope.successMessage = '';
          if (!scope.hasNoPassword && scope.inputs.oldPassword.length < 7) {
            scope.errorMessage = 'Your current password is not correct.';
          } else if (scope.inputs.newPassword1 !== scope.inputs.newPassword2) {
            scope.errorMessage = 'Your new passwords do not match.';
          } else if (scope.inputs.newPassword1.length < 7) {
            scope.errorMessage = 'Your password needs to be longer than 7 characters.';
          } else if (!scope.hasNoPassword && scope.inputs.oldPassword === scope.inputs.newPassword1) {
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

        scope.$watch(function() {
          return scope.prefs.has_no_password;
        }, function(hasNoPassword) {
          scope.hasNoPassword = Boolean(hasNoPassword);
          scope.passwordAction = scope.hasNoPassword ? 'Set a password' : 'Change your password';
        });

      }
    };
  }
]);
