'use strict';

angular.module('kifi')

.controller('LoggedInHeaderCtrl', [
  '$scope', '$rootElement', '$analytics', '$rootScope', '$document', 'installService', 'profileService', 'libraryService',
  '$location', 'util', 'KEY', 'modalService', '$timeout', '$state', 'mobileOS', '$window', 'extensionLiaison',
  function (
    $scope, $rootElement, $analytics, $rootScope, $document, installService, profileService, libraryService,
    $location, util, KEY, modalService, $timeout, $state, mobileOS, $window, extensionLiaison) {

    $scope.showExtensionInstall = !installService.installedVersion && installService.canInstall;
    $scope.hasExtension = !!installService.installedVersion;
    $scope.search = {text: $state.params.q || '', focused: false, suggesting: false, libraryChip: false};
    $scope.me = profileService.me;

    // TODO: Remove callout when most users know about search suggestions (Mar 17 2015)
    $timeout(function () {
      $scope.calloutVisible = profileService.prefs.site_notify_libraries_in_search && !$scope.search.text && !$scope.search.suggesting;
    }, 2400);

    $scope.closeCallout = function () {
      $scope.calloutVisible = false;
      profileService.prefs.site_notify_libraries_in_search = false;
      profileService.savePrefs({site_notify_libraries_in_search: false});
    };

    $scope.viewGuide = function() {
      extensionLiaison.triggerGuide();
    };

    $scope.triggerExtensionInstall = function () {
      installService.triggerInstall(function () {
        modalService.open({
          template: 'common/modal/installExtensionErrorModal.tpl.html'
        });
      });
    };

    $scope.showMobileInterstitial = (mobileOS === 'iOS' || mobileOS === 'Android');

    $scope.maybeShowWindingDown = function(event) {
      event.preventDefault();
      if (profileService.shouldBeWindingDown()) {
        modalService.showWindingDownModal();
        return true;
      } else {
        return false;
      }
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
        $scope.calloutVisible = false;
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
      if (!$scope.search.suggesting && !$scope.search.focused) {
        if ($scope.calloutVisible) {
          $scope.closeCallout();
        }
        $scope.search.suggesting = true;
      }
      $scope.search.focused = true;
    };
    $scope.onInputBlur = function ($event) {
      var focused = document.activeElement === $event.target;  // blur fires if window loses focus, even if input doesn't
      $scope.search.focused = focused;
      $scope.search.suggesting = focused;
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
    }

    $scope.onQueryChange = function () {
      $scope.search.suggesting = true;
    };

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
      $scope.search.suggesting = false;
      if ($state.is('search')) {
        $state.go('home.feed');
      } else if ($state.is('library.search')) {
        $state.go('^.keeps');
      }
      restoreLibraryChip();
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
          if ($scope.search.suggesting && ($state.current.name === 'search' || $state.current.name === 'library.search')) {
            $scope.search.suggesting = false;
            $scope.search.text = $state.params.q;
            restoreLibraryChip();
          } else {
            $scope.clearInput();
            $timeout(function () {  // Angular throws an exception if an event is triggered during $digest/$apply
              angular.element(document.activeElement).filter('.kf-lih-search-input').blur();
            });
          }
          break;
      }
    };

    $scope.addKeeps = function () {
      var library = $scope.library;
      modalService.open({
        template: 'keeps/addKeepModal.tpl.html',
        modalData: {selectedLibId: library && library.permissions.indexOf('add_keeps') !== -1 && library.id}
      });
    };

    $scope.createLibrary = function () {
      if ($state.includes('*.libraries.**')) {
        $rootScope.$broadcast('openCreateLibrary');
      } else {
        $state.go('userProfile.libraries.own', { handle: $scope.me.username, openCreateLibrary: true });
      }
    };

    $scope.createTeam = function () {
      $analytics.eventTrack('user_clicked_page', {
        type: $location.path(),
        action: 'clickedCreateTeamPlusDropDown'
      });
      $state.go('teams.new');
    };

    $scope.importBookmarks = function () {
      var kifiVersion = $window.document.documentElement.getAttribute('data-kifi-ext');

      if (!kifiVersion) {
        modalService.open({
          template: 'common/modal/installExtensionModal.tpl.html',
          scope: $scope
        });
        return;
      }

      $rootScope.$emit('showGlobalModal', 'importBookmarks');
      $analytics.eventTrack('user_clicked_page', {
        'type': 'yourKeeps',
        'action': 'clickedImportBrowserSideNav'
      });
    };

    $scope.importBookmarkFile = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
      $analytics.eventTrack('user_clicked_page', {
        'type': 'yourKeeps',
        'action': 'clicked3rdPartySideNav'
      });
    };


    function onDocKeyDown(e) {
      if (!e.isDefaultPrevented()) {
        switch (e.which) {
          case KEY.ENTER:
            if (e.metaKey && !e.shiftKey && !e.altKey && !e.ctrlKey) {
              e.preventDefault();
              $scope.$apply(function () {
                $scope.addKeeps();
              });
            }
            break;
          case 191: // '/'
            if (!e.metaKey && !e.shiftKey && !e.altKey && !e.ctrlKey &&
                !angular.element(document.activeElement).is('input,textarea,[contenteditable],[contenteditable] *')) {
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
