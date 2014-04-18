'use strict';

angular.module('kifi.layout.main', [
  'kifi.undo',
  'angulartics'
])

.controller('MainCtrl', [
  '$scope', '$element', '$window', '$location', '$timeout', '$rootElement', 'undoService', 'keyIndices', 'injectedState', '$rootScope', '$analytics',
  function ($scope, $element, $window, $location, $timeout, $rootElement, undoService, keyIndices, injectedState, $rootScope, $analytics) {

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

    $rootScope.$on('showGlobalModal', function (e, modal) {
      switch (modal) {
        case 'addNetworks':
          $scope.modal = 'add_networks';
          $scope.data.showAddNetworks = true;
          break;
        case 'importBookmarks':
          initBookmarkImport.apply(null, Array.prototype.slice(arguments, 2));
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
    }

    if (/^Mac/.test($window.navigator.platform)) {
      $rootElement.find('body').addClass('mac');
    }
  }
]);
