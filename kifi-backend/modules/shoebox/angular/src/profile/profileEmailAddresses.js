'use strict';

angular.module('kifi.profileEmailAddresses', ['util', 'kifi.profileService'])

.directive('kfProfileEmailAddresses', [
  '$document', 'keyIndices', 'profileService',
  function ($document, keyIndices, profileService) {
    return {
      restrict: 'A',
      scope: {
        state: '=inputState',
        emailList: '=',
        validateEmailAction: '&',
        addEmailAction: '&',
        resendVerificationEmailAction: '&'
      },
      templateUrl: 'profile/profileEmailAddresses.tpl.html',
      link: function (scope, element) {
        scope.isOpen = false;
        scope.emailWithActiveDropdown = null;
        scope.showEmailDeleteDialog = {};
        scope.emailToBeDeleted = null;

        scope.toggle = function () {
          scope.isOpen = !scope.isOpen;
        };

        scope.enableAddEmail = function () {
          scope.state.editing = true;
        };

        scope.validateEmail = function (value) {
          return scope.validateEmailAction({value: value});
        };

        scope.addEmail = function (value) {
          return scope.addEmailAction({value: value});
        };

        scope.resendVerificationEmail = function (value) {
          return scope.resendVerificationEmailAction({value: value});
        };

        scope.openDropdownForEmail = function (event, email) {
          if (scope.emailWithActiveDropdown !== email) {
            scope.emailWithActiveDropdown = email;
            event.stopPropagation();
            $document.bind('click', closeDropdown);
          }
        };

        scope.deleteEmail = function (email) {
          scope.emailToBeDeleted = email;
          scope.showEmailDeleteDialog.value = true;
        };

        scope.confirmDeleteEmail = function () {
          profileService.deleteEmailAccount(scope.emailToBeDeleted);
        };
        
        scope.makePrimary = function (email) {
          profileService.makePrimary(email);
        };

        element.find('input')
          .on('keydown', function (e) {
            switch (e.which) {
              case keyIndices.KEY_ESC:
                scope.$apply(scope.cancelAddEmail);
                break;
              case keyIndices.KEY_ENTER:
                scope.$apply(scope.addEmail);
                break;
            }
          });

        function closeDropdown() {
          scope.$apply(function () { scope.emailWithActiveDropdown = null; });
          $document.unbind('click', closeDropdown);
        }
      }
    };
  }
]);
