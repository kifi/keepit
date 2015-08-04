'use strict';

angular.module('kifi')

.directive('kfProfileImage', [
  '$document', '$timeout', '$window', '$q', '$http', '$state', 'env', 'modalService',
  'profileService', 'orgProfileService', '$analytics', '$location',
  function ($document, $timeout, $window, $q, $http, $state, env, modalService,
            profileService, orgProfileService, $analytics, $location) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        picUrl: '=',
        uploadUrl: '@',
        profile: '='
      },
      templateUrl: 'common/modal/photoUpload/profileImage.tpl.html',
      link: function (scope, element) {
        var maskOffset = 40, maskSize;
        var positioning = {};
        var dragging = {};
        var isImageLoaded = false;
        var PHOTO_BINARY_UPLOAD_URL = env.xhrBase + (scope.uploadUrl ||  '/user/pic/upload');
        var PHOTO_CROP_UPLOAD_URL = env.xhrBase + (scope.uploadUrl || '/user/pic');
        var photoXhr2;

        function refreshZoom() {
          if (!isImageLoaded) {
            return;
          }
          var scale = Math.pow(scope.zoomSlider.value / scope.zoomSlider.max, 2);
          positioning.currentWidth = positioning.minimumWidth + scale * (positioning.maximumWidth - positioning.minimumWidth);
          positioning.currentHeight = positioning.minimumHeight + scale * (positioning.maximumHeight - positioning.minimumHeight);
          imageElement.css({backgroundSize: positioning.currentWidth + 'px ' + positioning.currentHeight + 'px'});
          updateOffset();
          updateImagePosition();
        }

        scope.zoomSlider = {
          orientation: 'horizontal',
          min: 0,
          max: 100,
          change: refreshZoom,
          slide: refreshZoom
        };

        function cappedLength(length, low, high) {
          if (length > low) {
            return low;
          }
          if (length < high) {
            return high;
          }
          return length;
        }

        function updateImagePosition() {
          imageElement.css({backgroundPosition: positioning.currentLeft + 'px ' + positioning.currentTop + 'px'});
        }

        function setPosition(left, top) {
          positioning.currentLeft = cappedLength(left, maskOffset, imageElement.width() - maskOffset - positioning.currentWidth);
          positioning.currentTop = cappedLength(top, maskOffset, imageElement.height() - maskOffset - positioning.currentHeight);
        }

        function updateOffset() {
          var left = imageElement.width() / 2 - positioning.currentWidth * positioning.centerXRatio;
          var top = imageElement.height() / 2 - positioning.currentHeight * positioning.centerYRatio;
          setPosition(left, top);
        }

        function updateRatio() {
          positioning.centerXRatio = (imageElement.width() / 2 - positioning.currentLeft) / positioning.currentWidth;
          positioning.centerYRatio = (imageElement.height() / 2 - positioning.currentTop) / positioning.currentHeight;
        }

        var fileInput = element.find('.profile-image-file');
        var imageElement, imageMask;

        function startImageDragging(e) {
          dragging.initialMouseX = e.pageX;
          dragging.initialMouseY = e.pageY;
          dragging.initialLeft = positioning.currentLeft;
          dragging.initialTop = positioning.currentTop;
          $document.on('mousemove', updateImageDragging);
          $document.on('mouseup', stopImageDragging);
        }

        function updateImageDragging(e) {
          var left = dragging.initialLeft + e.pageX - dragging.initialMouseX;
          var top = dragging.initialTop + e.pageY - dragging.initialMouseY;
          setPosition(left, top);
          updateImagePosition();
        }

        function stopImageDragging() {
          $document.off('mousemove', updateImageDragging);
          $document.off('mousemove', stopImageDragging);
          updateRatio();
        }

        function setupImageDragging() {
          imageMask.on('mousedown', startImageDragging);
        }

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
              deferred.resolve($window.JSON.parse(xhr.responseText));
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
          isImageLoaded = false;
          scope.files = files;
          if (scope.files.length === 0) {
            return;
          }
          // Using a local file reader so that the user can edit the image without uploading it to the server first
          var reader = new FileReader();
          reader.onload = function (e) {
            showImageEditingTool(e.target.result);
          };
          reader.readAsDataURL(scope.files[0]);
        };

        function showImageEditingTool(imageUrl) {
          modalService.open({
            template: 'common/modal/photoUpload/imageEditModal.tpl.html',
            scope: scope
          });

          // Use $timeout here so that the DOM elements in the edit-image modal
          // can be loaded and found.
          $timeout(function () {
            imageElement = $document.find('.kf-profile-image-dialog-image');
            imageMask = $document.find('.kf-profile-image-dialog-mask');
            setupImageDragging();

            imageElement.css({
              background: 'url(' + imageUrl + ') no-repeat'
            });
            maskSize = imageElement.width() - 2 * maskOffset;
            var image = new Image();
            image.onload = function () {
              var img = this;
              scope.$apply(function () {
                positioning.imageWidth = img.width || 1;
                positioning.imageHeight = img.height || 1;
                var imageRatio = positioning.imageWidth / positioning.imageHeight;
                var maxZoomFactor = 1.5;
                if (imageRatio < 1) {
                  positioning.minimumWidth = maskSize;
                  positioning.minimumHeight = positioning.minimumWidth / imageRatio;
                  positioning.maximumWidth = maxZoomFactor * Math.max(positioning.imageWidth, maskSize);
                  positioning.maximumHeight = positioning.maximumWidth / imageRatio;
                } else {
                  positioning.minimumHeight = maskSize;
                  positioning.minimumWidth = positioning.minimumHeight * imageRatio;
                  positioning.maximumHeight = maxZoomFactor * Math.max(positioning.imageHeight, maskSize);
                  positioning.maximumWidth = positioning.maximumHeight * imageRatio;
                }
                scope.zoomSlider.value = 50;
                positioning.centerXRatio = 0.5;
                positioning.centerYRatio = 0.5;
                //
                isImageLoaded = true;
                refreshZoom();
              });
            };
            image.src = imageUrl;
          }, 0);
        }

        scope.resetChooseImage = function () {
          fileInput.val(null);
        };

        function imageUploadError() {
          scope.$broadcast('forceCloseModal');

          // Use $timeout to wait for forceClose to close the currently open modal before
          // opening the next modal.
          $timeout(function () {

            modalService.open({
              template: 'common/modal/photoUpload/imageUploadFailedModal.tpl.html'
            });
            scope.resetChooseImage();
          }, 0);
        }

        scope.uploadImage = function () {
          modalService.open({
            template: 'common/modal/photoUpload/imageUploadingModal.tpl.html',
            scope: scope
          });

          var upload;
          var scaling = positioning.imageWidth / positioning.currentWidth;
          var data = {
            picWidth: positioning.imageWidth,
            picHeight: positioning.imageHeight,
            cropX: Math.floor(scaling * (maskOffset - positioning.currentLeft)),
            cropY: Math.floor(scaling * (maskOffset - positioning.currentTop)),
            cropSize: Math.floor(scaling * maskSize)
          };

          if (scope.uploadUrl && scope.uploadUrl.indexOf('organization') !== -1) {
            upload = orgProfileService.uploadOrgAvatar(scope.profile.id, data.cropX, data.cropY, data.picWidth, data.picHeight, scope.files[0]);
            upload.then(function () {
              scope.$broadcast('forceCloseModal');

              scope.resetChooseImage();
              $analytics.eventTrack('user_clicked_page', {
                'action': 'uploadImage',
                'path': $location.path()
              });

              $state.reload(); // get the newest profile data
            })
            ['catch'](imageUploadError);
          } else {
            upload = uploadPhotoXhr2(scope.files);
            if (upload) {
              upload.promise.then(function (result) {
                data.picToken = result && result.token;

                $http.post(PHOTO_CROP_UPLOAD_URL, data)
                .then(function () {
                  profileService.fetchMe();
                  scope.$broadcast('forceCloseModal');

                  scope.resetChooseImage();
                  $analytics.eventTrack('user_clicked_page', {
                    'action': 'uploadImage',
                    'path': $location.path()
                  });
                }, imageUploadError);
              }, imageUploadError);
            } else {
              imageUploadError();
            }
          }
        };
      }
    };
  }
]);
