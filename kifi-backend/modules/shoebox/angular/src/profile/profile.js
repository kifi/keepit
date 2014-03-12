'use strict';

angular.module('kifi.profile', [
  'util',
  'kifi.profileService',
  'kifi.profileInput',
  'kifi.routeService',
  'kifi.profileEmailAddresses',
  'kifi.profileChangePassword',
  'jun.facebook'
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
  '$scope', '$http', 'profileService', 'routeService',
  function ($scope, $http, profileService, routeService) {
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
  'profileService', '$window',
  function (profileService, $window) {
    return {
      restrict: 'A',
      link: function (scope) {
        // TODO: implement this. look at how facebook is done
        //profileService.getLinkedInStatus();

        scope.isLinkedInConnected = function () {
          return scope.me && scope.me.linkedinStatus === 'connected';
        };

        scope.connectLinkedIn = function () {
          $window.location.href = '/link/linkedin';
        };

        scope.disconnectLinkedIn = function () {
          // todo: disconnect
        };
      }
    };
  }
])

.directive('kfFacebookConnectButton', [
  'profileService', '$FB', '$window',
  function (profileService, $FB, $window) {
    return {
      restrict: 'A',
      link: function (scope) {
        profileService.getFacebookStatus();

        scope.isFacebookConnected = function () {
          return profileService.me.facebookStatus === 'connected';
        };

        scope.connectFacebook = function () {
          $FB.login()['finally'](profileService.getFacebookStatus).then(function () {
            $window.location.href = '/link/facebook';
          });
        };

        scope.disconnectFacebook = function () {
          // todo: disconnect
          $FB.disconnect()['finally'](profileService.getFacebookStatus);
        };
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
])

.directive('kfProfileImage', [
  '$compile', '$templateCache', '$window', '$q', '$http', 'env',
  function ($compile, $templateCache, $window, $q, $http, env) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        picUrl: '='
      },
      templateUrl: 'profile/profileImage.tpl.html',
      link: function (scope, element) {
        var fileInput = element.find('input');

        var URL = $window.URL || $window.webkitURL,
          PHOTO_BINARY_UPLOAD_URL = env.xhrBase + '/user/pic/upload',
          PHOTO_CROP_UPLOAD_URL = env.xhrBase + '/user/pic';

        var photoXhr2;

        function uploadPhotoXhr2(files) {
          var file = Array.prototype.filter.call(files, isImage)[0];
          if (file) {
            if (photoXhr2) {
              photoXhr2.abort();
            }

            var xhr = new $window.XMLHttpRequest();
            photoXhr2 = xhr;

            var deferred = $q.defer();

            xhr.withCredentials = true;
            xhr.upload.addEventListener('progress', function (e) {
              if (e.lengthComputable) {
                deferred.notify(e.loaded / e.total);
              }
            });

            xhr.addEventListener('load', function () {
              deferred.resolve(JSON.parse(xhr.responseText));
            });

            xhr.addEventListener('loadend', function () {
              if (photoXhr2 === xhr) {
                photoXhr2 = null;
              }
              //todo(martin) We cannot directly check the state of the promise
              /*if (deferred.state() === 'pending') {
                deferred.reject();
              }*/
            });

            xhr.open('POST', PHOTO_BINARY_UPLOAD_URL, true);
            xhr.send(file);

            return {
              file: file,
              promise: deferred.promise
            };
          }

          //todo(martin): Notify user
        }

        function isImage(file) {
          return file.type.search(/^image\/(?:bmp|jpg|jpeg|png|gif)$/) === 0;
        }

        scope.selectFile = function () {
          fileInput.click();
        };

        scope.fileChosen = function (files) {
          var upload = uploadPhotoXhr2(files);
          if (upload) {
            var localPhotoUrl = URL.createObjectURL(upload.file);
            var img = new $window.Image();
            img.onload = function () {
              var image = this;
              upload.promise.then(function (result) {
                $http.post(PHOTO_CROP_UPLOAD_URL, {
                  picToken: result && result.token,
                  picWidth: image.width,
                  picHeight: image.height,
                  cropX: image.x,
                  cropY: image.y,
                  cropSize: Math.min(image.width, image.height)
                })
                .then(function () {
                  scope.picUrl = result.url;
                });
              });
            };
            img.src = localPhotoUrl;
          }
        };
      }
    };
  }
]);
