'use strict';

angular.module('kifi.profileInput', ['util', 'kifi.profileService'])

.directive('kfProfileInput', [
  '$timeout', '$http', 'keyIndices', 'util', 'profileService', 'routeService',
  function ($timeout, $http, keyIndices, util, profileService, routeService) {
    return {
      restrict: 'A',
      scope: {
        isEmail: '=inputIsEmail',
        state: '=inputState'
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

        function setInvalid(header, body) {
          scope.state.invalid = true;
          scope.errorHeader = header || '';
          scope.errorBody = body || '';
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
          var value = scope.state.value ? scope.state.value.trim().replace(/\s+/g, ' ') : '';
          if (scope.isEmail) {
            if (!value) {
              setInvalid('This field is required');
              return;
            } else if (!util.validateEmail(value)) {
              setInvalidEmailAddressError();
              return;
            } else {
              scope.state.invalid = false;
            }
          }

          scope.state.prevValue = scope.state.currentValue;
          updateValue(value);

          scope.state.editing = false;

          if (scope.isEmail) {
            saveNewPrimaryEmail(value);
          } else {
            profileService.postMe({
              description: scope.state.value
            });
          }
        };

        // Email input utility functions

        function setInvalidEmailAddressError() {
          setInvalid('Invalid email address', 'Please enter a valid email address');
        }

        function checkCandidateEmailSuccess(me, emailInfo) {
          if (emailInfo.isPrimary || emailInfo.isPendingPrimary) {
            profileService.fetchMe();
            return;
          }
          if (emailInfo.isVerified) {
            return profileService.setNewPrimaryEmail(me, emailInfo.address);
          }
          // email is available || (not primary && not pending primary && not verified)
          //todo showEmailChangeDialog(email, setNewPrimaryEmail(email), cancel);
        }

        function checkCandidateEmailError(status) {
          switch (status) {
          case 400: // bad format
            setInvalidEmailAddressError();
            break;
          case 403: // belongs to another user
            setInvalid(
              'This email address is already taken',
              'This email address belongs to another user.<br>Please enter another email address.'
            );
            break;
          }
          updateValue(scope.state.prevValue);
        }

        function saveNewPrimaryEmail(email) {
          profileService.getMe().then(function (me) {
            if (me.primaryEmail.address === email) {
              return;
            }

            // todo(martin) move this http call outside of the directive
            $http({
              url: routeService.emailInfoUrl,
              method: 'GET',
              params: {
                email: email
              }
            })
            .success(function (data) {
              checkCandidateEmailSuccess(me, data);
            })
            .error(function (data, status) {
              checkCandidateEmailError(status);
            });
          });
        }
      }
    };
  }
]);
