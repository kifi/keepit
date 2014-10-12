'use strict';

angular.module('kifi')

.controller('MainCtrl', [
  '$scope', '$element', '$window', '$location', '$timeout', '$rootElement', 'undoService', 'keyIndices',
  'injectedState', '$rootScope', '$analytics', 'installService', 'profileService', '$q', 'routeService',
  'modalService', 'libraryService',
  function ($scope, $element, $window, $location, $timeout, $rootElement, undoService, keyIndices,
    injectedState, $rootScope, $analytics, installService, profileService, $q, routeService,
    modalService, libraryService) {

    $scope.search = {};
    $scope.searchEnabled = false;
    $scope.data = $scope.data || {};
    $scope.editMode = {
      enabled: false
    };

    // For populating libraries in the import modals.
    $scope.librariesEnabled = libraryService.isAllowed();

    if ($scope.librariesEnabled) {
      libraryService.fetchLibrarySummaries(true).then(function () {
        $scope.libraries = _.filter(libraryService.librarySummaries, function(lib) {
          return lib.access !== 'read_only';
        });
        $scope.selection = $scope.selection || {};
        $scope.selection.library = _.find($scope.libraries, { 'name': 'Main Library' });
        $scope.libSelectTopOffset = 220;
      });
    }

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

    $scope.$on('$routeChangeSuccess', function (event, current, previous) {
      if (previous && current && previous.controller === 'SearchCtrl' && current.controller !== 'SearchCtrl') {
        $scope.search.text = '';
      }
    });

    $scope.undo = undoService;

    var messages = {
      0: 'Welcome back!',
      2: 'Bookmark import in progress. Reload the page to update.'
    };

    function handleInjectedState(state) {
      if (state) {
        if (state.m && state.m === '1') {
          $scope.showEmailVerifiedModal = true;
        } else if (state.m) { // show small tooltip
          var msg = messages[state.m];
          $scope.tooltipMessage = msg;
          $timeout(function () {
            delete $scope.tooltipMessage;
          }, 5000);
        }
      }
    }
    handleInjectedState(injectedState.state);

    function initBookmarkImport(count, msgEvent) {
      if ($scope.librariesEnabled) {
         modalService.open({
          template: 'common/modal/importBookmarksLibraryModal.tpl.html',
          scope: $scope
        });
      } else {
        modalService.open({
          template: 'common/modal/importBookmarksModal.tpl.html',
          scope: $scope
        });
      }

      $scope.msgEvent = (msgEvent && msgEvent.origin && msgEvent.source && msgEvent) || false;
    }

    function initBookmarkFileUpload() {
      // make sure file input is empty
      var fileInput = $rootElement.find('.bookmark-file-upload');
      fileInput.replaceWith(fileInput = fileInput.clone(true));

      if ($scope.librariesEnabled) {
         modalService.open({
          template: 'common/modal/importBookmarkFileLibraryModal.tpl.html',
          scope: $scope
        });
      } else {
        modalService.open({
          template: 'common/modal/importBookmarkFileModal.tpl.html',
          scope: $scope
        });
      }
    }

    $rootScope.$on('showGlobalModal', function (e, modal) {
      switch (modal) {
        case 'importBookmarks':
          initBookmarkImport.apply(null, Array.prototype.slice(arguments, 2));
          break;
        case 'importBookmarkFile':
          initBookmarkFileUpload();
          break;
      }
    });

    $scope.importBookmarksToLibrary = function (library) {
      $scope.forceClose = true;

      // Use $evalAsync to wait for forceClose to close the currently open modal before opening
      // the next modal.
      $scope.$evalAsync(function () {
        // var kifiVersion = $window.document.getElementsByTagName('html')[0].getAttribute('data-kifi-ext');

        // if (!kifiVersion) {
        //   modalService.open({
        //     template: 'common/modal/importBookmarksErrorModal.tpl.html'
        //   });
        //   return;
        // }

        // $analytics.eventTrack('user_clicked_page', {
        //   'type': 'browserImport',
        //   'action': makePublic ? 'ImportPublic' : 'ImportPrivate'
        // });

        // var event = $scope.msgEvent && $scope.msgEvent.origin && $scope.msgEvent.source && $scope.msgEvent;
        // var message = 'import_bookmarks';
        // if (makePublic) {
        //   message = 'import_bookmarks_public';
        // }
        // if (event) {
        //   event.source.postMessage(message, $scope.msgEvent.origin);
        // } else {
        //   $window.postMessage(message, '*');
        // }

        // Fake check. To do: use real endpoints to determine which modal to open.
        if (library) {
          modalService.open({
            template: 'common/modal/importBookmarksLibraryInProgressModal.tpl.html',
            scope: $scope
          });
        } else {
          modalService.open({
            template: 'common/modal/importBookmarksErrorModal.tpl.html'
          });
        }
      });
    };

    $scope.importBookmarks = function (makePublic) {
      $scope.forceClose = true;

      // Use $evalAsync to wait for forceClose to close the currently open modal before opening
      // the next modal.
      $scope.$evalAsync(function () {
        var kifiVersion = $window.document.getElementsByTagName('html')[0].getAttribute('data-kifi-ext');

        if (!kifiVersion) {
          modalService.open({
            template: 'common/modal/importBookmarksErrorModal.tpl.html'
          });
          return;
        }

        $analytics.eventTrack('user_clicked_page', {
          'type': 'browserImport',
          'action': makePublic ? 'ImportPublic' : 'ImportPrivate'
        });

        var event = $scope.msgEvent && $scope.msgEvent.origin && $scope.msgEvent.source && $scope.msgEvent;
        var message = 'import_bookmarks';
        if (makePublic) {
          message = 'import_bookmarks_public';
        }
        if (event) {
          event.source.postMessage(message, $scope.msgEvent.origin);
        } else {
          $window.postMessage(message, '*');
        }

        modalService.open({
          template: 'common/modal/importBookmarksInProgressModal.tpl.html'
        });

      });
    };

    $scope.cancelImport = function () {
      $window.postMessage('import_bookmarks_declined', '*');
    };

    $scope.disableBookmarkImport = true;

    $scope.allowUpload = function (elem) {
      $scope.$apply(function () {
        var file = elem && elem.files && elem.files[0];
        if (file && file.name.indexOf('.html', file.name.length - 5) !== -1) { // checking if file.name ends with '.html'
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

    function uploadBookmarkFileHelper(file, makePublic) {
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
        xhr.open('POST', routeService.uploadBookmarkFile(makePublic), true);
        xhr.send(file);
      } else {
        deferred.reject({'error': 'no file'});
      }
      return deferred.promise;
    }

    $scope.uploadBookmarkFile = function ($event, makePublic) {
      if (!$scope.disableBookmarkImport) {
        var $file = $rootElement.find('.bookmark-file-upload');
        var file = $file && $file[0] && $file[0].files && $file[0].files[0];
        if (file) {
          $scope.disableBookmarkImport = true;

          $analytics.eventTrack('user_clicked_page', {
            'type': '3rdPartyImport',
            'action': makePublic ? 'ImportPublic' : 'ImportPrivate'
          });

          var tooSlowTimer = $timeout(function () {
            $scope.importFileStatus = 'Your bookmarks are still uploading... Hang tight.';
            $scope.disableBookmarkImport = false;
          }, 20000);

          $scope.importFileStatus = 'Uploading! May take a bit, especially if you have a lot of links.';
          $scope.importFilename = '';

          uploadBookmarkFileHelper(file, makePublic).then(function success(result) {
            $timeout.cancel(tooSlowTimer);
            $scope.importFileStatus = '';

            $scope.forceClose = true;

            // Use $evalAsync to wait for forceClose to close the currently open modal before
            // opening the next modal.
            $scope.$evalAsync(function () {
              if (!result.error) { // success!
                modalService.open({
                  template: 'common/modal/importBookmarkFileInProgressModal.tpl.html'
                });
              } else { // hrmph.
                modalService.open({
                  template: 'common/modal/importBookmarkFileErrorModal.tpl.html'
                });
              }
            });

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

    $scope.importBookmarkFileToLibrary = function (library) {
      $scope.forceClose = true;

      $scope.$evalAsync(function () {
        // This check is a fake check. Remove when we have real endpoints.
        if (library) {
          modalService.open({
            template: 'common/modal/importBookmarkFileLibraryInProgressModal.tpl.html'
          });
        } else {
          modalService.open({
            template: 'common/modal/importBookmarkFileErrorModal.tpl.html'
          });
        }
      });
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

    $scope.editKeepsLabel = function () {
      if ($scope.editMode.enabled) {
        return 'Done editing';
      } else {
        return 'Edit keeps';
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

    $scope.showDelightedSurvey = function () {
      return profileService.prefs && profileService.prefs.show_delighted_question;
    };

    /**
     * Make the page "extension-friendly"
     */
    var htmlElement = angular.element(document.getElementsByTagName('html')[0]);
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
