'use strict';

angular.module('kifi.profile', [
  'util',
  'kifi.profileService',
  'kifi.profileInput',
  'kifi.routeService',
  'kifi.profileEmailAddresses',
  'kifi.profileChangePassword',
  'kifi.profileImage',
  'jun.facebook',
  'angulartics'
])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/profile', {
      templateUrl: 'profile/profile.tpl.html',
      controller: 'ProfileCtrl'
    });
  }
])

.controller('ProfileCtrl', [
  '$scope', '$http', 'profileService', 'routeService', '$window', 'socialService',
  function ($scope, $http, profileService, routeService, $window, socialService) {

    // $analytics.eventTrack('test_event', { category: 'test', label: 'controller' });

    $window.document.title = 'Kifi • Your Profile';
    socialService.refresh();

    $scope.showEmailChangeDialog = {value: false};
    $scope.showResendVerificationEmailDialog = {value: false};

    profileService.getMe().then(function (data) {
      $scope.me = data;
    });

    $scope.descInput = {};
    $scope.$watch('me.description', function (val) {
      $scope.descInput.value = val || '';
    });

    $scope.emailInput = {};
    $scope.$watch('me.primaryEmail.address', function (val) {
      $scope.emailInput.value = val || '';
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

    $scope.validateEmail = function (value) {
      return profileService.validateEmailFormat(value);
    };

    $scope.saveEmail = function (email) {
      if ($scope.me && $scope.me.primaryEmail.address === email) {
        return profileService.successInputActionResult();
      }

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

    $scope.exportKeeps = function() {
      $scope.exported = true;
    };

    $scope.getExportUrl = function () {
      return routeService.exportKeeps;
    };

    $scope.getExportButtonText = function() {
      if ($scope.exported === true) {
        return 'Export Again';
      } else {
        return 'Export Keeps';
      }
    };

    function showVerificationAlert(email) {
      $scope.emailForVerification = email;
      $scope.showResendVerificationEmailDialog.value = true;
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
        return profileService.setNewPrimaryEmail($scope.me, emailInfo.address);
      }
      // email is available || (not primary && not pending primary && not verified)
      emailToBeSaved = email;
      $scope.showEmailChangeDialog.value = true;
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

/*
.directive('kfExportKeeps', [
  'profileService', '$window', 'env', 'socialService',
  function (profileService, $window, env, socialService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {},
      link: function (scope) {
        scope.addressBookImportText = 'Export Keeps';
        socialService.refresh().then(function () {

        });
        scope.exportKeeps = socialService.exportKeeps;
      }
    }
  }
])*/

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
]);
