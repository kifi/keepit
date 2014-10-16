'use strict';

angular.module('kifi')

.controller('ProfileCtrl', [
  '$scope', '$http', 'modalService', 'profileService', 'routeService', '$window', 'socialService', '$rootScope',
  function ($scope, $http, modalService, profileService, routeService, $window, socialService, $rootScope) {

    // $analytics.eventTrack('test_event', { category: 'test', label: 'controller' });

    $window.document.title = 'Kifi • Your Profile';
    socialService.refresh();

    $scope.me = profileService.me;
    profileService.getMe();

    $scope.descInput = {};
    $scope.$watch('me.description', function (val) {
      $scope.descInput.value = val || '';
    });

    $scope.emailInput = {};
    $scope.$watch('me.primaryEmail.address', function (val) {
      $scope.emailInput.value = val || '';
    });

    $scope.name = {};
    $scope.$watch('me.firstName', function (val) {
      $scope.name.firstName = val;
    });
    $scope.$watch('me.lastName', function (val) {
      $scope.name.lastName = val;
    });

    $scope.addEmailInput = {};

    $scope.logout = function () {
      profileService.logout();
    };

    $scope.saveDescription = function (value) {
      profileService.postMe({
        description: value
      });
    };

    $scope.validateName = function (name) {
      return profileService.validateNameFormat(name);
    };

    $scope.saveName = function (name) {
      profileService.setNewName(name);
    };

    $scope.validateEmail = function (value) {
      return profileService.validateEmailFormat(value);
    };

    $scope.saveEmail = function (email) {
      return getEmailInfo(email).then(function (result) {
        return checkCandidateEmailSuccess(email, result.data);
      }, function (result) {
        return profileService.getEmailValidationError(result.status);
      });
    };

    $scope.addEmail = function (email) {
      return getEmailInfo(email).then(function (result) {
        return checkCandidateAddEmailSuccess(email, result.data);
      }, function (result) {
        return profileService.getEmailValidationError(result.status);
      });
    };

    $scope.isUnverified = function (email) {
      return email.value && !email.value.isPendingPrimary && email.value.isPrimary && !email.value.isVerified;
    };

    $scope.resendVerificationEmail = function (email) {
      if (!email && $scope.me && $scope.me.primaryEmail) {
        email = $scope.me.primaryEmail.address;
      }
      showVerificationAlert(email);
      profileService.resendVerificationEmail(email);
    };

    $scope.cancelPendingPrimary = function () {
      profileService.cancelPendingPrimary();
    };

    // Profile email utility functions
    var emailToBeSaved;

    $scope.cancelSaveEmail = function () {
      $scope.emailInput.value = $scope.me.primaryEmail.address;
    };

    $scope.confirmSaveEmail = function () {
      profileService.setNewPrimaryEmail(emailToBeSaved);
    };

    function showVerificationAlert(email) {
      $scope.emailForVerification = email;
      modalService.open({
        template: 'profile/emailResendVerificationModal.tpl.html',
        scope: $scope
      });
    }

    function getEmailInfo(email) {
      return $http({
        url: routeService.emailInfoUrl,
        method: 'GET',
        params: {
          email: email
        }
      });
    }

    function checkCandidateEmailSuccess(email, emailInfo) {
      if (emailInfo.isPrimary || emailInfo.isPendingPrimary) {
        profileService.fetchMe();
        return;
      }
      if (emailInfo.isVerified) {
        return profileService.setNewPrimaryEmail(emailInfo.address);
      }
      // email is available || (not primary && not pending primary && not verified)
      emailToBeSaved = email;

      modalService.open({
        template: 'profile/emailChangeModal.tpl.html',
        scope: $scope
      });
      return profileService.successInputActionResult();
    }

    function checkCandidateAddEmailSuccess(email, emailInfo) {
      if (emailInfo.status === 'available') {
        profileService.addEmailAccount(email);
        showVerificationAlert(email); // todo: is the verification triggered automatically?
      }
      else {
        return profileService.failureInputActionResult(
          'This email address is already added',
          'Please use another email address.'
        );
      }
    }

    $rootScope.$emit('libraryUrl', {});
  }
])

.directive('kfLinkedinConnectButton', [
  'socialService',
  function (socialService) {
    return {
      restrict: 'A',
      link: function (scope) {
        scope.isLinkedInConnected = socialService.linkedin && !!socialService.linkedin.profileUrl;

        scope.linkedin = socialService.linkedin;

        scope.$watch(function () {
          return socialService.linkedin && socialService.linkedin.profileUrl;
        }, function () {
          var linkedin = socialService.linkedin;
          if (linkedin && linkedin.profileUrl) {
            scope.isLinkedInConnected = true;
            scope.liProfileUrl = linkedin.profileUrl;
          } else {
            scope.isLinkedInConnected = false;
            scope.liProfileUrl = '';
          }
        });

        scope.connectLinkedIn = socialService.connectLinkedIn;
        scope.disconnectLinkedIn = socialService.disconnectLinkedIn;
      }
    };
  }
])

.directive('kfFacebookConnectButton', [
  'socialService',
  function (socialService) {
    return {
      restrict: 'A',
      link: function (scope) {
        scope.isFacebookConnected = socialService.facebook && !!socialService.facebook.profileUrl;

        scope.facebook = socialService.facebook;

        scope.$watch(function () {
          return socialService.facebook && socialService.facebook.profileUrl;
        }, function () {
          var facebook = socialService.facebook;
          if (facebook && facebook.profileUrl) {
            scope.isFacebookConnected = true;
            scope.fbProfileUrl = facebook.profileUrl;
          } else {
            scope.isFacebookConnected = false;
            scope.fbProfileUrl = '';
          }
        });

        scope.connectFacebook = socialService.connectFacebook;
        scope.disconnectFacebook = socialService.disconnectFacebook;
      }
    };
  }
])

.directive('kfEmailImport', [
  'profileService', '$window', 'env', 'socialService',
  function (profileService, $window, env, socialService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'profile/emailImport.tpl.html',
      link: function (scope) {

        scope.addressBookImportText = 'Import a Gmail account';

        socialService.refresh().then(function () {
          scope.addressBooks = socialService.addressBooks;
          if (socialService.addressBooks && socialService.addressBooks.length > 0) {
            scope.addressBookImportText = 'Import another Gmail account';
          }
        });

        scope.importGmailContacts = socialService.importGmail;
      }
    };
  }
])

.directive('kfProfileExportKeeps', [
  'routeService',
  function (routeService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'profile/profileExportKeeps.tpl.html',
      link: function (scope) {
        scope.getExportUrl = function () {
          return routeService.exportKeeps;
        };

        scope.exportKeeps = function () {
          scope.exported = true;
        };

        scope.getExportButtonText = function () {
          return scope.exported ? 'Export Again' : 'Export Keeps';
        };
      }
    };
  }
])

.directive('kfProfileManageAccount', [
  '$http', 'profileService', '$analytics', '$location',
  function ($http, profileService, $analytics, $location) {
    return {
      restrict: 'A',
      scope: {},
      templateUrl: 'profile/profileManageAccount.tpl.html',
      link: function (scope) {
        scope.isOpen = { 'uninstall': false, 'close': false, 'export': false };
        scope.toggle = function (target) {
          scope.isOpen[target] = !scope.isOpen[target];
        };

        scope.getCloseAccountButtonText = function () {
          return (scope.closeAccountStatus === 'error') && 'Retry' ||
            (scope.closeAccountStatus === 'pending') && 'Sending...' ||
            (scope.closeAccountStatus === 'sent') && 'Message Sent' ||
            'Close Account';
        };

        scope.closeAccount = function () {
          // prevent multiple attempts
          if (scope.closeAccountStatus) { return false; }

          scope.closeAccountStatus = 'pending';
          var data = { comment: scope.comment };
          profileService.closeAccountRequest(data).then(function () {
            scope.closeAccountStatus = 'sent';

            $analytics.eventTrack('user_clicked_page', {
              'action': 'clickCloseAccount',
              'path': $location.path()
            });
          }, function () {
            scope.closeAccountStatus = 'error';
          });
          return false;
        };

        scope.isCloseAccountStatus = function (status) {
          return scope.closeAccountStatus === status;
        };
      }
    };
  }
])
;
