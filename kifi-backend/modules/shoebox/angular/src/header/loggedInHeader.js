'use strict';

angular.module('kifi')

.controller('LoggedInHeaderCtrl', [
  '$scope', '$window', '$rootElement', '$rootScope', '$document', 'profileService',
  '$location', 'util', 'keyIndices', 'modalService', '$timeout', '$state', 'routeService',
  function ($scope, $window, $rootElement, $rootScope, $document, profileService,
    $location, util, keyIndices, modalService, $timeout, $state, routeService) {

    $scope.toggleMenu = function () {
      // libraryMenu is inherited from AppCtrl scope.
      $scope.libraryMenu.visible = !$scope.libraryMenu.visible;
    };

    $scope.library = {};
    $scope.search = {text: '', focused: false, libraryChip: false};

    // Temp callout method. Remove after most users know about libraries. (Oct 26 2014)
    var calloutName = 'tag_callout_shown';
    $scope.showCallout = function () {
      return profileService.prefs.site_intial_show_library_intro && !profileService.prefs[calloutName];
    };
    $scope.closeCallout = function () {
      var save = { 'site_show_library_intro': false };
      save[calloutName] = true;
      profileService.prefs[calloutName] = true;
      profileService.savePrefs(save);
    };


    //
    // Watchers & Listeners
    //
    [
      $rootScope.$on('libraryOnPage', function (e, library) {
        $scope.library = library;
        $scope.libOwnerPicUrl = library && routeService.formatPicUrl(library.owner.id, library.owner.pictureName, 100);
        $scope.libOwnerProfileUrl = library && routeService.getProfileUrl(library.owner.username);

        if (library) {
          $scope.search.libraryChip = true;
        } else if ($scope.search.libraryChip) {
          removeLibraryChip();
        } else if ($state.params && $state.params.q && util.startsWith($state.params.q, 'tag:')) {
          $scope.search.text = $state.params.q;
        }
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
      if ($location.path() === '/find') {
        $location.search('q', $scope.search.text).replace(); // this keeps any existing URL params
      } else if ($scope.search.libraryChip) {
        if ($scope.search.text) {
          if ($state.params.q) {
            $location.search('q', $scope.search.text).replace();
          } else {
            $location.url($scope.library.url + '/find?q=' + $scope.search.text + '&f=a');
          }
        } else {
          $location.url($scope.library.url);
        }
      } else if ($scope.search.text) {
        $location.url('/find?q=' + $scope.search.text);
      }
    }

    $scope.search.text = $state.params.q || '';
    $scope.onQueryChange = util.$debounce($scope, reactToQueryChange, 250);

    $scope.onSearchBarClicked = function () {
      if ($scope.library && $scope.library.id) {
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
