'use strict';

angular.module('kifi')

.controller('MainCtrl', [
  '$scope', '$element', '$window', '$location', '$timeout', '$rootElement', 'undoService', 'keyIndices',
  'initParams', '$rootScope', '$analytics', 'installService', 'profileService', '$q', 'routeService',
  'modalService', 'libraryService',
  function ($scope, $element, $window, $location, $timeout, $rootElement, undoService, keyIndices,
    initParams, $rootScope, $analytics, installService, profileService, $q, routeService,
    modalService, libraryService) {

    var tooltipMessages = {
      0: 'Welcome back!',
      2: 'Bookmark import in progress. Reload the page to update.'
    };

    $scope.search = {};
    $scope.searchEnabled = false;
    $scope.data = $scope.data || {};
    $scope.editMode = {
      enabled: false
    };

    $scope.enableSearch = function () {
      $scope.searchEnabled = true;
      // add event handler on the inheriting scope
      this.$on('$destroy', function () {
        $scope.searchEnabled = false;
      });
    };

    $scope.isEmpty = function () {
      return !$scope.search.text;
    };

    $scope.onKeydown = function (e) {
      if (e.keyCode === keyIndices.KEY_ESC) {
        $scope.clear();
      } else if (e.keyCode === keyIndices.KEY_ENTER) {
        performSearch();
      }
    };

    $scope.clear = function () {
      $scope.search.text = '';
      performSearch();
    };

    $scope.clearable = function () {
      return !!$scope.search.text;
    };

    function performSearch() {
      var text = $scope.search.text || '';
      text = _.str.trim(text);

      if (text) {
        $location.path('/find').search('q', text);
      }
      else {
        $location.path('/').search('');
      }

      // hacky solution to url event not getting fired
      $timeout(function () {
        $scope.$apply();
      });
    }

    $scope.onChange = _.debounce(performSearch, 350);

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

      $scope.msgEvent = (msgEvent && msgEvent.origin && msgEvent.source && msgEvent) || false;
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

        var event = $scope.msgEvent;
        var message = {
          type: 'import_bookmarks',
          libraryId: library.id
        };

        if (event) {
          event.source.postMessage(message, event.origin);
        } else {
          $window.postMessage(message, '*');
        }

        modalService.open({
          template: 'common/modal/importBookmarksInProgressModal.tpl.html'
        });
      }, 0);
    };

    $scope.cancelImport = function () {
      $window.postMessage('import_bookmarks_declined', '*');
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


    var deregisterPrefsChangedListener = $rootScope.$on('prefsChanged', function () {
      $scope.showDelightedSurvey = profileService.prefs && profileService.prefs.show_delighted_question;
    });
    $scope.$on('$destroy', deregisterPrefsChangedListener);

    /**
     * Make the page "extension-friendly"
     */
    var htmlElement = angular.element(document.documentElement);
    // override right margin to always be 0
    htmlElement.css({marginRight: 0});
    $rootScope.$watch(function () {
      return htmlElement[0].getAttribute('kifi-pane-parent') !== null;
    }, function (res) {
      var mainElement = $rootElement.find('.kf-main');
      var rightCol = $rootElement.find('.kf-col-right');
      var header = $rootElement.find('.kf-header-inner');
      if (res) {
        // find the margin-right rule that should have been applied
        var fakeHtml = angular.element(document.createElement('html'));
        fakeHtml.attr({
          'kifi-pane-parent':'',
          'kifi-with-pane':''
        });
        fakeHtml.hide().appendTo('html');
        var marginRight = fakeHtml.css('margin-right');
        fakeHtml.remove();

        var currentRightColWidth = rightCol.width();
        if (Math.abs(parseInt(marginRight,10) - currentRightColWidth) < 15) {
          // avoid resizing if the width difference would be too small
          marginRight = currentRightColWidth + 'px';
        }
        mainElement.css('width', 'calc(100% - ' + marginRight + ')');
        rightCol.css('width', fakeHtml.css('margin-right'));
        header.css('padding-right', marginRight);
      } else {
        mainElement.css('width', '');
        rightCol.css('width', '');
        header.css('padding-right', '');
      }
    });
  }
]);
