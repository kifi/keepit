'use strict';

angular.module('kifi')

.controller('LoggedInHeaderCtrl', [
  '$scope', '$window', '$rootElement', '$rootScope', '$document', 'profileService',
  '$location', 'util', 'keyIndices', 'modalService', '$timeout', '$state', 'routeService',
  function ($scope, $window, $rootElement, $rootScope, $document, profileService,
    $location, util, keyIndices, modalService, $timeout, $state, routeService) {

    $scope.toggleMenu = function () {
      if ($scope.showCallout()) {
        $scope.closeCallout();
      }

      // libraryMenu is inherited from AppCtrl scope.
      $scope.libraryMenu.visible = !$scope.libraryMenu.visible;
    };

    $scope.search = {text: '', focused: false, libraryChip: false};


    // TODO: Remove callout when most users know about library menu (Feb 9 2014)
    $scope.showCallout = angular.noop;
    $timeout(function () {
      $scope.showCallout = function () {
        return profileService.prefs.site_introduce_library_menu && !$scope.libraryMenu.visible;
      };
    }, 2400);

    $scope.closeCallout = function () {
      profileService.prefs.site_introduce_library_menu = false;
      profileService.savePrefs({site_introduce_library_menu: false});
    };


    //
    // Watchers & Listeners
    //
    [
      $rootScope.$on('libraryOnPage', function (e, library) {
        $scope.library = library;
        $scope.libOwnerPicUrl = library && routeService.formatPicUrl(library.owner.id, library.owner.pictureName, 100);
        $scope.libOwnerProfileUrl = library && routeService.getProfileUrl(library.owner.username);
        $scope.search.libraryChip = !!library;
      }),

      $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams) {
        $scope.search.text = toState.name === 'library.search' || toState.name === 'search' ? toParams.q : '';
      }),

      $rootScope.$on('triggerAddKeep', function (e, library) {
        $scope.addKeeps(library);
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    $scope.onInputFocus = function () {
      $scope.search.focused = true;
    };
    $scope.onInputBlur = function ($event) {
      var focused = $window.document.activeElement === $event.target;
      $scope.search.focused = focused;
      if ($scope.library && !$scope.search.libraryChip && !focused && !$scope.search.text) {
        $scope.search.libraryChip = true;
      }
    };

    $scope.onClickLibX = function () {
      removeLibraryChip();
      $timeout(function () {
        angular.element('.kf-lih-search-input').focus();
      });
    };

    function removeLibraryChip() {
      $scope.search.libraryChip = false;
      reactToQueryChange();
    }

    function reactToQueryChange() {
      if ($state.is('search') || $state.is('library.search')) {
        $rootScope.$emit('searchTextUpdated', $scope.search.text, !!$scope.search.libraryChip && $scope.library.url);
      } else {
        $state.go($scope.search.libraryChip ? 'library.search' : 'search', {q: $scope.search.text});
      }
    }

    // Use $state.params instead of $stateParams because this controller has no access
    // to the search parameters on the search controller via $stateParams.
    // See: http://stackoverflow.com/questions/23081397/ui-router-stateparams-vs-state-params
    $scope.search.text = $state.params.q || '';
    $scope.onQueryChange = util.$debounce($scope, reactToQueryChange, 250);

    $scope.$on('$destroy', $rootScope.$on('newQueryFromLocation', function (e, query) {
      $scope.search.text = query;
    }));

    $scope.onSearchBarClicked = function () {
      if ($scope.library) {
        $rootScope.$emit('trackLibraryEvent', 'click', {
          action: 'clickedSearchBar'
        });
      }
    };

    $scope.clearInput = function () {
      $scope.search.text = '';
      reactToQueryChange();
    };

    var KEY_ESC = 27, KEY_DEL = 8;
    $scope.onKeydown = function (e) {
      switch (e.keyCode) {
        case KEY_DEL:
          if ($scope.search.text === '') {
            removeLibraryChip();
          }
          break;
        case KEY_ESC:
          $scope.clearInput();
          break;
      }
    };

    $scope.me = profileService.me;
    $scope.me.picUrl = $scope.me.picUrl || '//www.kifi.com/assets/img/ghost.200.png';

    $scope.addKeeps = function (library) {
      modalService.open({
        template: 'keeps/addKeepsModal.tpl.html',
        modalData: { currentLib : library }
      });
    };

    function addKeepsShortcut(e) {
      $scope.$apply(function () {
        if (e.metaKey && e.which === keyIndices.KEY_ENTER) {
          $scope.addKeeps();
        }
      });
    }
    $document.on('keydown', addKeepsShortcut);
    $scope.$on('$destroy', function () {
      $document.off('keydown', addKeepsShortcut);
    });

    $scope.userProfileUrl = routeService.getProfileUrl($scope.me.username);
    $scope.logoutUrl = routeService.logoutUrl;
  }
]);
