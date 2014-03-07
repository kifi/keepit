'use strict';

angular.module('kifi.profile', ['kifi.profileService', 'kifi.routeService', 'kifi.profileInput', 'kifi.routeService', 'util'])

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
  '$scope', '$http', 'profileService', 'routeService', 'util',
  function ($scope, $http, profileService, routeService, util) {

    $scope.showEmailChangeDialog = {value: false};

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

    function failureInputActionResult(errorHeader, errorBody) {
      return {
        isSuccess: false,
        error: {
          header: errorHeader,
          body: errorBody
        }
      };
    }

    function successInputActionResult() {
      return {isSuccess: true};
    }

    $scope.saveDescription = function (value) {
      profileService.postMe({
        description: value
      });
    };

    $scope.validateEmail = function (value) {
      if (!value) {
        return failureInputActionResult('This field is required');
      } else if (!util.validateEmail(value)) {
        return invalidEmailValidationResult();
      }
      return successInputActionResult();
    };

    $scope.saveEmail = function (email) {
      if ($scope.me && $scope.me.primaryEmail.address === email) {
        return successInputActionResult();
      }

      return $http({
        url: routeService.emailInfoUrl,
        method: 'GET',
        params: {
          email: email
        }
      })
      .then(function (result) {
        return checkCandidateEmailSuccess(email, result.data);
      }, function (result) {
        return checkCandidateEmailError(result.status);
      });
    };

    // Profile email utility functions
    var emailToBeSaved;

    $scope.cancelSaveEmail = function () {
      $scope.emailInput.value = $scope.me.primaryEmail.address;
    };

    $scope.confirmSaveEmail = function () {
      profileService.setNewPrimaryEmail(emailToBeSaved);
    };

    function invalidEmailValidationResult() {
      return failureInputActionResult('Invalid email address', 'Please enter a valid email address');
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
      return successInputActionResult();
    }

    function checkCandidateEmailError(status) {
      switch (status) {
        case 400: // bad format
          return invalidEmailValidationResult();
        case 403: // belongs to another user
          return failureInputActionResult(
            'This email address is already taken',
            'This email address belongs to another user.<br>Please enter another email address.'
          );
      }
    }
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

        profileService.getAddressBooks().then(function (data) {
          scope.addressBooks = data;
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
