'use strict';

angular.module('kifi.profile', ['util', 'kifi.profileService', 'kifi.validatedInput'])

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

    function getPrimaryEmail(emails) {
      return _.find(emails, 'isPrimary') || emails[0] || null;
    }

    profileService.getMe().then(function (data) {
      $scope.me = data;
      $scope.primaryEmail = getPrimaryEmail(data.emails);
    });
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
  '$timeout', 'keyIndices', 'util',
  function ($timeout, keyIndices, util) {
    return {
      restrict: 'A',
      scope: {
        templateUrl: '@',
        defaultValue: '=',
        isEmail: '='
      },
      templateUrl: 'profile/profileInput.tpl.html',
      link: function (scope, element) {
        scope.onKeydown = function (e) {
          switch (e.keyCode) {
          case keyIndices.KEY_ESC:
            scope.disableEditing();
            break;
          }
        };

        scope.shouldFocus = false;
        scope.enabled = false;
        scope.isInvalid = false;
        scope.input = {};

        function updateValue(value) {
          scope.input.value = value;
          scope.currentValue = value;
        }

        function setInvalid(header, body) {
          scope.isInvalid = true;
          scope.errorHeader = header;
          scope.errorBody = body;
        }

        function setValid() {
          scope.isInvalid = false;
        }

        scope.$watch('defaultValue', updateValue);

        scope.enableEditing = function () {
          scope.saveButton.css('display', 'block');
          scope.shouldFocus = true;
          scope.enabled = true;
        };

        scope.disableEditing = function () {
          scope.input.value = scope.currentValue;
          scope.saveButton.css('display', 'none');
          scope.enabled = false;
        };

        scope.saveInput = function () {
          // Validate input
          var value = scope.input.value ? scope.input.value.trim().replace(/\s+/g, ' ') : '';
          if (scope.isEmail) {
            if (!value) {
              setInvalid('This field is required', '');
              return;
            } else if (!util.validateEmail(value)) {
              setInvalid('Invalid email address', 'Please enter a valid email address');
              return;
            } else {
              setValid();
            }
          }

          updateValue(value);
          if (scope.isEmail) {
            // todo(martin) saveNewPrimaryEmail($input, function () { revertInput($input); });
          } else {
            // todo(martin) saveProfileInfo()
          }

        };

        scope.blurInput = function () {
          // give enough time for saveInput() to fire. todo(martin): find a more reliable solution
          $timeout(function () {
            scope.disableEditing();
          }, 100);
        };

        $timeout(function () {
          scope.editButton = angular.element(element[0].querySelector('.profile-input-edit'));
          scope.saveButton = angular.element(element[0].querySelector('.profile-input-save'));
        });
      }
    };
  }
]);
