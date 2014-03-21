'use strict';

angular.module('kifi.profileImage', [])

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
        scope.showImageEditDialog = {value: false};
        var fileInput = element.find('.profile-image-file');

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
          // this function is called via onchange attribute in input field - we need to let angular know about it
          scope.$apply(function () {
            scope.files = files;
            scope.showImageEditDialog.value = true;
          });
        };

        scope.cancelChooseImage = function () {
          fileInput.val(null);
        };

        scope.uploadImage = function () {
          var upload = uploadPhotoXhr2(scope.files);
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
