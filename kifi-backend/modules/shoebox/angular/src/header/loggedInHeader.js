'use strict';

angular.module('kifi')

.controller('LoggedInHeaderCtrl', [
  '$scope', '$rootElement', '$rootScope', '$document', 'profileService', 'libraryService',
  '$location', 'util', 'keyIndices', 'modalService', '$timeout', '$state',
  function (
    $scope, $rootElement, $rootScope, $document, profileService, libraryService,
    $location, util, keyIndices, modalService, $timeout, $state) {

    var hasSuggExp = profileService.me.experiments.indexOf('search_suggestions') >= 0;

    $scope.toggleMenu = function () {
      if ($scope.calloutVisible) {
        $scope.closeCallout();
      }

      $scope.libraryMenuVisible = !$scope.libraryMenuVisible;
    };

    $scope.libraryMenuVisible = false;
    $scope.search = {text: $state.params.q || '', focused: false, suggesting: false, libraryChip: false};
    $scope.me = profileService.me;

    // TODO: Remove callout when most users know about library menu (Feb 9 2014)
    $timeout(function () {
      $scope.calloutVisible = profileService.prefs.site_introduce_library_menu && !$scope.libraryMenuVisible;
    }, 2400);

    $scope.closeCallout = function () {
      $scope.calloutVisible = false;
      profileService.prefs.site_introduce_library_menu = false;
      profileService.savePrefs({site_introduce_library_menu: false});
    };


    //
    // Watchers & Listeners
    //
    [
      $rootScope.$on('libraryOnPage', function (e, library) {
        $scope.library = library;
        $scope.search.libraryChip = !!library;
      }),

      $rootScope.$on('$stateChangeStart', function () {
        $scope.search.transitionOff = true;
        if ($scope.calloutVisible) {
          $scope.closeCallout();
        }
      }),

      $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams) {
        $scope.search.text = toState.name === 'library.search' || toState.name === 'search' ? toParams.q : '';
        $timeout(reenableSearchTransition, 300);
      }),

      $rootScope.$on('$stateChangeError', function () {
        $timeout(reenableSearchTransition, 300);
      }),

      $rootScope.$on('triggerAddKeep', function () {
        $scope.addKeeps();
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    function reenableSearchTransition() {
      $scope.search.transitionOff = false;
    }

    $scope.onInputFocus = function () {
      if (!$scope.search.suggesting && hasSuggExp) {
        $scope.search.suggesting = !$scope.search.focused;
      }
      $scope.search.focused = true;
    };
    $scope.onInputBlur = function ($event) {
      var focused = document.activeElement === $event.target;  // blur fires if window loses focus, even if input doesn't
      $scope.search.focused = focused;
      $scope.search.suggesting = focused && hasSuggExp;
      if (!focused && !$scope.search.text) {
        restoreLibraryChip();
      }
    };

    $scope.onMouseDownLibX = function (e) {
      if (angular.element(document.activeElement).hasClass('kf-lih-search-input')) {
        e.preventDefault();  // keep input focused
        removeLibraryChip();
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
      if (!hasSuggExp) {
        reactToQueryChange();
      }
    }

    $scope.onQueryChange = hasSuggExp ? function () {
      $scope.search.suggesting = true;
    } : util.$debounce($scope, reactToQueryChange, 250);

    function reactToQueryChange() {
      if ($state.is('search') || $state.is('library.search')) {
        $rootScope.$emit('searchTextUpdated', $scope.search.text, !!$scope.search.libraryChip && $scope.library.url);
      } else {
        $state.go($scope.search.libraryChip ? 'library.search' : 'search', {q: $scope.search.text});
      }
    }

    function restoreLibraryChip() {
      $scope.search.libraryChip = !!$scope.library;
    }

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

    $scope.clearInput = function (event) {
      $scope.search.text = '';
      if (hasSuggExp) {
        $scope.search.suggesting = false;
        if ($state.is('search')) {
          $state.go('home');
        } else if ($state.is('library.search')) {
          $state.go('^.keeps');
        }
        restoreLibraryChip();
      } else {
        reactToQueryChange();
      }
      if (event) {
        event.preventDefault(); // prevents search input from getting focus
      }
    };

    $scope.onKeydown = function (e) {
      switch (e.which) {
        case 8:  // del
          if ($scope.search.text === '') {
            removeLibraryChip();
          }
          break;
        case 27:  // esc
          if (hasSuggExp && ($state.name === 'search' || $state.name === 'library.search')) {
            $scope.search.suggesting = false;
            $scope.search.text = $state.params.q || '';
            restoreLibraryChip();
          } else {
            $scope.clearInput();
          }
          $timeout(function () {  // Angular throws an exception if an event is triggered during $digest/$apply
            angular.element(document.activeElement).filter('.kf-lih-search-input').blur();
          });
          break;
      }
    };

    $scope.addKeeps = function () {
      var library = $scope.library;
      modalService.open({
        template: 'keeps/addKeepsModal.tpl.html',
        modalData: {selectedLibId: library && libraryService.isMyLibrary(library) && library.id}
      });
    };

    function onDocKeyDown(e) {
      if (!e.isDefaultPrevented()) {
        switch (e.which) {
          case keyIndices.KEY_ENTER:
            if (e.metaKey && !e.shiftKey && !e.altKey && !e.ctrlKey) {
              e.preventDefault();
              $scope.$apply(function () {
                $scope.addKeeps();
              });
            }
            break;
          case 191: // '/'
            if (!e.metaKey && !e.shiftKey && !e.altKey && !e.ctrlKey && !angular.element(document.activeElement).hasClass('kf-lih-search-input')) {
              e.preventDefault();
              angular.element('.kf-lih-search-input').focus();
            }
            break;
        }
      }
    }
    $document.on('keydown', onDocKeyDown);
    $scope.$on('$destroy', function () {
      $document.off('keydown', onDocKeyDown);
    });
  }
]);
