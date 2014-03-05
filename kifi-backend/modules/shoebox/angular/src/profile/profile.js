'use strict';

angular.module('kifi.profile', ['util', 'kifi.profileService', 'kifi.routeService'])

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
  '$scope', 'profileService',
  function ($scope, profileService) {

    profileService.getMe().then(function (data) {
      $scope.me = data;
    });

    $scope.descInput = {};
    $scope.$watch('me.description', function (val) {
      $scope.descInput.value = val || '';
      console.log('updateDesc', val);
    });

    $scope.emailInput = {};
    $scope.$watch('me.primaryEmail.address', function (val) {
      $scope.emailInput.value = val || '';
      console.log('updatePrimary', val);
    });
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
        var fileInput = angular.element($templateCache.get('profileImageFile.html'));
        element.append(fileInput);
        $compile(fileInput)(scope);

        var URL = $window.URL || $window.webkitURL;
        var PHOTO_BINARY_UPLOAD_URL = env.xhrBase + '/user/pic/upload';
        var PHOTO_CROP_UPLOAD_URL = env.xhrBase + '/user/pic';

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
            return {file: file, promise: deferred.promise};
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
                }).then(function () {
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
])

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

        console.log('profile input', scope.state, scope.isEmail);

        var cancelEditPromise;

        element.find('input')
          .on('keydown', function (e) {
            console.log('keydown', e.which);
            switch (e.which) {
            case keyIndices.KEY_ESC:
              scope.$apply(function () {
                scope.cancel();
              });
              break;
            case keyIndices.KEY_ENTER:
              scope.$apply(function () {
                scope.save();
              });
              break;
            }
          })
          .on('blur', function () {
            // give enough time for save() to fire. todo(martin): find a more reliable solution
            console.log('blur');
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
          console.log('edit', scope.state.value);
          cancelCancelEdit();
          scope.state.currentValue = scope.state.value;
          scope.state.editing = true;
        };

        scope.cancel = function () {
          console.log('cancel', scope.state.value, scope.state.currentValue);
          scope.state.value = scope.state.currentValue;
          scope.state.editing = false;
        };

        scope.save = function () {
          // Validate input
          var value = scope.state.value ? scope.state.value.trim().replace(/\s+/g, ' ') : '';
          console.log('save', value, scope.state.currentValue);
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
