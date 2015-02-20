'use strict';

angular.module('kifi')

.controller('MainCtrl', [
  '$scope', '$rootScope', '$window', '$timeout', '$rootElement', '$q',
  'initParams', 'undoService', 'installService', 'profileService', 'routeService',
  'modalService', 'libraryService', 'extensionLiaison',
  function ($scope, $rootScope, $window, $timeout, $rootElement, $q,
    initParams, undoService, installService, profileService, routeService,
    modalService, libraryService, extensionLiaison) {

    var importBookmarksMessageEvent;
    var tooltipMessages = {
      0: 'Welcome back!',
      2: 'Bookmark import in progress. Reload the page to update.'
    };

    $scope.data = $scope.data || {};
    $scope.editMode = {
      enabled: false
    };

    $scope.undo = undoService;

    if (initParams.m === '1') {
      $scope.showEmailVerifiedModal = true;
    } else if (initParams.m in tooltipMessages) { // show small tooltip
      $scope.tooltipMessage = tooltipMessages[initParams.m];
      $timeout(function () {
        delete $scope.tooltipMessage;
      }, 5000);
    }

    //
    // For importing bookmarks
    // TODO(yiping): Modularize this code; all this flat code in Main Ctrl is not good. :(
    //
    $scope.onImportLibrarySelected = function (selectedLibrary) {
      $scope.importLibrary = selectedLibrary;
    };

    function initBookmarkImport(count, msgEvent) {
      // Display the Main Library as the default option.
      $scope.importLibrary = _.find(libraryService.librarySummaries, { 'kind': 'system_main' });

      modalService.open({
        template: 'common/modal/importBookmarksModal.tpl.html',
        scope: $scope
      });

      importBookmarksMessageEvent = msgEvent;
    }

    function initBookmarkFileUpload() {
      // Make sure file input is empty.
      var fileInput = $rootElement.find('.bookmark-file-upload');
      fileInput.replaceWith(fileInput = fileInput.clone(true));

      // Display the Main Library as the default option.
      $scope.importLibrary = _.find(libraryService.librarySummaries, { 'kind': 'system_main' });

      modalService.open({
        template: 'common/modal/importBookmarkFileModal.tpl.html',
        scope: $scope
      });
    }

    var deregisterGlobalModal = $rootScope.$on('showGlobalModal', function (e, modal) {
      switch (modal) {
        case 'importBookmarks':
          initBookmarkImport.apply(null, Array.prototype.slice(arguments, 2));
          break;
        case 'importBookmarkFile':
          initBookmarkFileUpload();
          break;
      }
    });
    $scope.$on('$destroy', deregisterGlobalModal);

    $scope.importBookmarksToLibrary = function (library) {
      $scope.forceClose = true;

      // Use $timeout to wait for forceClose to close the currently open modal before opening
      // the next modal.
      $timeout(function () {
        $scope.forceClose = false;

        var kifiVersion = $window.document.documentElement.getAttribute('data-kifi-ext');

        if (!kifiVersion) {
          modalService.open({
            template: 'common/modal/importBookmarksErrorModal.tpl.html'
          });
          return;
        }

        // TODO(yiping): update this when we have the full tracking spec.
        // $analytics.eventTrack('user_clicked_page', {
        //   'type': 'browserImport',
        //   'action': '???'
        // });

        extensionLiaison.importBookmarksTo(library.id, importBookmarksMessageEvent);
        importBookmarksMessageEvent = null;

        modalService.open({
          template: 'common/modal/importBookmarksInProgressModal.tpl.html'
        });
      }, 0);
    };

    $scope.cancelImport = function () {
      extensionLiaison.declineBookmarkImport(importBookmarksMessageEvent);
      importBookmarksMessageEvent = null;
    };

    $scope.disableBookmarkImport = true;

    $scope.allowUpload = function (elem) {
      $scope.$apply(function () {
        var file = elem && elem.files && elem.files[0];
        var ext = file && file.name.split('.').pop();
        if (ext && (ext === 'html' || ext === 'htm' || ext === 'zip')) {
          $scope.importFilename = file.name;
          $scope.disableBookmarkImport = false;
          $scope.importFileStatus = '';
        } else {
          $scope.importFilename = '';
          $scope.disableBookmarkImport = true;
          $scope.importFileStatus = 'Invalid bookmark file (*.html). Try picking it again.';
        }
      });
    };

    $scope.openExportPopup = function ($event) {
      var url = $event.target.href;
      $window.open(url, '', 'menubar=no,location=yes,resizable=yes,scrollbars=yes,status=no,width=1000,height=500');
      $event.preventDefault();
      return false;
    };

    function uploadBookmarkFileToLibraryHelper(file, libraryId) {
      var deferred = $q.defer();
      if (file) {
        var xhr = new XMLHttpRequest();
        xhr.withCredentials = true;
        xhr.upload.addEventListener('progress', function (e) {
          deferred.notify({'name': 'progress', 'event': e});
        });
        xhr.addEventListener('load', function () {
          deferred.resolve(JSON.parse(xhr.responseText));
        });
        xhr.addEventListener('error', function (e) {
          deferred.reject(e);
        });
        xhr.addEventListener('loadend', function (e) {
          deferred.notify({'name': 'loadend', 'event': e});
        });
        xhr.open('POST', routeService.uploadBookmarkFileToLibrary(libraryId), true);
        xhr.send(file);
      } else {
        deferred.reject({'error': 'no file'});
      }
      return deferred.promise;
    }

    $scope.importBookmarkFileToLibrary = function (library) {
      if (!$scope.disableBookmarkImport) {
        var $file = $rootElement.find('.bookmark-file-upload');
        var file = $file && $file[0] && $file[0].files && $file[0].files[0];
        if (file) {
          $scope.disableBookmarkImport = true;

          // TODO(yiping): update this when we have the full tracking spec.
          // $analytics.eventTrack('user_clicked_page', {
          //   'type': '3rdPartyImport',
          //   'action': '???'
          // });

          var tooSlowTimer = $timeout(function () {
            $scope.importFileStatus = 'Your bookmarks are still uploading... Hang tight!';
            $scope.disableBookmarkImport = false;
          }, 8000);

          $scope.importFileStatus = 'Uploading! May take a bit, especially if you have a lot of links.';
          $scope.importFilename = '';

          uploadBookmarkFileToLibraryHelper(file, library.id).then(function success(result) {
            $timeout.cancel(tooSlowTimer);
            $scope.importFileStatus = '';

            $scope.forceClose = true;

            // Use $timeout to wait for forceClose to close the currently open modal before
            // opening the next modal.
            $timeout(function () {
              $scope.forceClose = false;

              if (!result.error) { // success!
                modalService.open({
                  template: 'common/modal/importBookmarkFileInProgressModal.tpl.html'
                });
              } else { // hrmph.
                modalService.open({
                  template: 'common/modal/importBookmarkFileErrorModal.tpl.html'
                });
              }
            }, 0);

          }, function fail() {
            $timeout.cancel(tooSlowTimer);
            $scope.disableBookmarkImport = false;
            $scope.importFileStatus = 'We may have had problems with your links. Reload the page to see if they’re coming in. ' +
              'If not, please contact support so we can fix it.';
          });
        } else {
          $scope.importFileStatus = 'Hm, couldn’t upload your file. Try picking it again.';
        }
      }
    };

    $scope.cancelBookmarkUpload = function () {
      $scope.disableBookmarkImport = true;
      $scope.importFilename = '';
      $scope.importFileStatus = '';
    };


    $scope.openBookmarkFileSelector = function () {
      // not great, but trying to fix an IE bug
      var bookmarkFileUpload = $rootElement.find('.bookmark-file-upload');
      bookmarkFileUpload.click();
    };

    // END For importing bookmarks.

    $scope.editKeepsLabel = function () {
      if ($scope.editMode.enabled) {
        return 'Done';
      } else {
        return 'Bulk keep to library';
      }
    };

    $scope.toggleEdit = function (moveWindow) {
      if (!$scope.editMode.enabled) {
        if (moveWindow) {
          $window.scrollBy(0, 118); // todo: scroll based on edit mode size. problem is that it's not on the page yet.
        }
      }
      $scope.editMode.enabled = !$scope.editMode.enabled;
    };

    if (/^Mac/.test($window.navigator.platform)) {
      $rootElement.find('body').addClass('mac');
    }

    function startGuide() {
      if (installService.installedVersion) {
        extensionLiaison.triggerGuide();
        profileService.savePrefs({auto_show_guide: null});
      }
    }

    function showPersonaModal() {
      modalService.open({
        template: 'persona/managePersonaUnescapableModal.tpl.html',
        modalData: {
          onClose: function() {
            profileService.savePrefs({auto_show_persona: null});
            if (profileService.prefs.auto_show_guide) { // start guide after showing persona modal
              startGuide();
            }
            $rootScope.$emit('refreshRecos');
          },
          finishText: 'Done'
        }
      });
    }

    $rootScope.$watchCollection(function () {
      return [profileService.prefs.auto_show_guide, profileService.prefs.auto_show_persona];
    }, function () {
      if (profileService.prefs.auto_show_persona) {
        showPersonaModal();
      } else if (profileService.prefs.auto_show_guide) {
        startGuide();
      }
    });
  }
]);
