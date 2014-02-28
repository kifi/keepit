'use strict';

angular.module('kifi.profile', ['util', 'kifi.profileService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/profile', {
      templateUrl: 'profile/profile.tpl.html',
      controller: 'ProfileCtrl'
    });
  }
])

.controller('ProfileCtrl', [
  '$scope', 'profileService',
  function ($scope, profileService) {

    var PRIMARY_INDEX = 0;

    function getPrimaryEmail(emails) {
      return _.find(emails, function (info) {
        return info.isPrimary;
      }) || emails[PRIMARY_INDEX] || null;
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
            var xhr = new XMLHttpRequest();
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
          } else {
            //todo(martin): Notify user
            //console.log("bad file");
          }
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
            var img = new Image();
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
]);
