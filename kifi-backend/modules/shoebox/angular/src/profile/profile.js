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
  '$scope', '$http', 'profileService', 'routeService', '$window',
  function ($scope, $http, profileService, routeService, $window) {

    // $analytics.eventTrack('test_event', { category: 'test', label: 'controller' });

    $window.document.title = 'Kifi • Your Profile';
    profileService.getNetworks();

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
  'profileService',
  function (profileService) {
    return {
      restrict: 'A',
      link: function (scope) {
        scope.isLinkedInConnected = (profileService.me && profileService.me.linkedInConnected) || false;

        scope.$watch(function () {
          return profileService.me.linkedInConnected;
        }, function (status) {
          scope.isLinkedInConnected = status;
          var li = _.find(profileService.networks, function (n) {
            return n.network === 'linkedin';
          });
          scope.liProfileUrl = li && li.profileUrl;
        });

        scope.connectLinkedIn = profileService.social.connectLinkedIn;
        scope.disconnectLinkedIn = profileService.social.disconnectLinkedIn;
      }
    };
  }
])

.directive('kfFacebookConnectButton', [
  'profileService',
  function (profileService) {
    return {
      restrict: 'A',
      link: function (scope) {
        scope.isFacebookConnected = (profileService.me && profileService.me.facebookConnected) || false;

        scope.$watch(function () {
          return profileService.me.facebookConnected;
        }, function (status) {
          scope.isFacebookConnected = status;
          var fb = _.find(profileService.networks, function (n) {
            return n.network === 'facebook';
          });
          scope.fbProfileUrl = fb && fb.profileUrl;
        });

        scope.connectFacebook = profileService.social.connectFacebook;
        scope.disconnectFacebook = profileService.social.disconnectFacebook;
      }
    };
  }
])

.directive('kfEmailImport', [
  'profileService', '$window', 'env',
  function (profileService, $window, env) {
    return {
      restrict: 'A',
      replace: true,
      scope: {},
      templateUrl: 'profile/emailImport.tpl.html',
      link: function (scope) {

        scope.addressBookImportText = 'Import a Gmail account';

        profileService.getAddressBooks().then(function (data) {
          scope.addressBooks = data;
          if (data && data.length > 0) {
            scope.addressBookImportText = 'Import another Gmail account';
          }
        });

        scope.importGmailContacts = function () {
          $window.location = env.origin + '/importContacts';
        };
      }
    };
  }
]);
