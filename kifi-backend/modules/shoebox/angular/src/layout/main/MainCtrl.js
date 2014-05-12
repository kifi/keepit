'use strict';

angular.module('kifi.layout.main', [
  'kifi.undo',
  'angulartics'
])

.controller('MainCtrl', [
  '$scope', '$element', '$window', '$location', '$timeout', '$rootElement', 'undoService', 'keyIndices',
  'injectedState', '$rootScope', '$analytics', 'keepService',
  function ($scope, $element, $window, $location, $timeout, $rootElement, undoService, keyIndices,
    injectedState, $rootScope, $analytics, keepService) {

    $scope.search = {};
    $scope.data = $scope.data || {};

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

    $scope.onFocus = function () {
      $scope.focus = true;
    };

    $scope.onBlur = function () {
      $scope.focus = false;
    };

    $scope.clear = function () {
      $scope.search.text = '';
      performSearch();
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

    var updateHeight = _.throttle(function () {
      $element.css('height', $window.innerHeight + 'px');
    }, 100);
    angular.element($window).resize(updateHeight);

    $timeout(updateHeight);

    var messages = {
      0: 'Welcome back!',
      2: 'Bookmark import in progress. Reload the page to update.'
    };

    function handleInjectedState(state) {
      if (state) {
        if (state.m && state.m === '1') {
          $scope.data.showEmailModal = true;
          $scope.modal = 'email';
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
      $scope.modal = 'import_bookmarks';
      $scope.data.showImportModal = true;
      $scope.msgEvent = (msgEvent && msgEvent.origin && msgEvent.source && msgEvent) || false;
    }

    function initBookmarkFileUpload() {
      $scope.modal = 'import_bookmark_file';
      $scope.data.showBookmarkFileModal1 = true;
    }

    $rootScope.$on('showGlobalModal', function (e, modal) {
      switch (modal) {
        case 'addNetworks':
          $scope.modal = 'add_networks';
          $scope.data.showAddNetworks = true;
          break;
        case 'importBookmarks':
          initBookmarkImport.apply(null, Array.prototype.slice(arguments, 2));
          break;
        case 'importBookmarkFile':
          initBookmarkFileUpload();
          break;
        case 'addKeeps':
          $scope.modal = 'add_keeps';
          $scope.data.showAddKeeps = true;
          break;
      }
    });

    $scope.importBookmarks = function () {
      $scope.data.showImportModal = false;

      var kifiVersion = $window.document.getElementsByTagName('html')[0].getAttribute('data-kifi-ext');

      if (!kifiVersion) {
        $scope.modal = 'import_bookmarks_error';
        $scope.data.showImportError = true;
        return;
      }

      $analytics.eventTrack('user_clicked_page', {
        'action': 'bookmarkImport'
      });

      var event = $scope.msgEvent && $scope.msgEvent.origin && $scope.msgEvent.source && $scope.msgEvent;
      if (event) {
        event.source.postMessage('import_bookmarks', $scope.msgEvent.origin);
      } else {
        $window.postMessage('import_bookmarks', '*');
      }
      $scope.modal = 'import_bookmarks2';
      $scope.data.showImportModal2 = true;
    };

    $scope.cancelImport = function () {
      $window.postMessage('import_bookmarks_declined', '*');
      $scope.data.showImportModal = false;
    };

    $scope.disableBookmarkImport = true;

    $scope.allowUpload = function (elem) {
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
    };

    $scope.openExportPopup = function($event, url) {
      $window.open(url, 'export_popup', 'menubar=no,location=yes,resizable=yes,scrollbars=yes,status=no,width=500,height=500');
      $event.preventDefault();
      return false;
    };

    $scope.uploadBookmarkFile = function ($event) {
      if (!$scope.disableBookmarkImport) {
        var $file = angular.element($event.target).parent().parent().find('input:file');
        var file = $file && $file[0] && $file[0].files && $file[0].files[0];
        if (file) {
          $scope.disableBookmarkImport = true;

          var tooSlowTimer = $timeout(function () {
            $scope.disableBookmarkImport = false;
            $scope.importFileStatus = 'Looks like we were too slow uploading your bookmarks. Try again?';
          }, 50000);

          $scope.importFileStatus = 'Uploading! May take a bit, especially if you have a lot links.';
          $scope.importFilename = '';

          keepService.uploadBookmarkFile(file).then(function (result) {
            $timeout.cancel(tooSlowTimer);
            $scope.importFileStatus = '';
            if (!result.error) { // success!
              $scope.data.showBookmarkFileModal1 = false;
              $scope.data.showBookmarkFileModal2 = true;
              $scope.modal = 'import_bookmark_file2';
            } else { // hrmph.
              $scope.modal = 'import_bookmarks_error';
              $scope.data.showBookmarkFileModal1 = false;
              $scope.data.showBookmarkFileError = true;
              $scope.modal = 'import_bookmark_error';
            }
          });
        } else {
          $scope.importFileStatus = 'Hm, couldn\'t upload your file. Try picking it again.';
        }
      }
    };

    $scope.cancelBookmarkUpload = function () {
      $scope.disableBookmarkImport = true;
      $scope.modal = '';
      $scope.importFilename = '';
      $scope.importFileStatus = '';
    };

    $scope.openBookmarkFileSelector = function ($event) {
      var $file = angular.element($event.target).parent().parent().find('input:file');
      $timeout(function () {
        $file.click();
      });
    };

    if (/^Mac/.test($window.navigator.platform)) {
      $rootElement.find('body').addClass('mac');
    }
  }
]);
