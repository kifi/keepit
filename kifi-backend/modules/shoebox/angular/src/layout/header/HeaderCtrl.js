'use strict';

angular.module('kifi')

.controller('HeaderCtrl', [
  '$scope', '$window', '$rootElement', '$rootScope', '$document', 'profileService', 'friendService',
    '$location', 'util', 'keyIndices', 'modalService', '$timeout', '$state', 'routeService',
  function ($scope, $window, $rootElement, $rootScope, $document, profileService, friendService,
    $location, util, keyIndices, modalService, $timeout, $state, routeService) {

    $scope.toggleMenu = function () {
      $rootElement.find('html').toggleClass('kf-sidebar-inactive');
    };

    // reminder: this triggers left sidebar to show in case the default for small windows is to hide left sidebar
    $window.addEventListener('message', function (event) {
      if (event.data === 'show_left_column') {  // for guide
        $scope.$apply(function () {
          $rootElement.find('html').removeClass('kf-sidebar-inactive');
        });
      }
    });

    $scope.isFocused = false;
    $scope.library = {};
    $scope.search = { text: '', showName: false };

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
    var deregisterLibraryChip = $rootScope.$on('libraryUrl', function (e, library) {
      $scope.library = library;

      if ($scope.library.owner && !$scope.library.owner.picUrl) {
        $scope.library.owner.picUrl = friendService.getPictureUrlForUser($scope.library.owner);
      }

      if ($scope.library.id) {
        $scope.search.showName = true;
      } else {
        $scope.clearLibraryName();
      }

      $scope.curatorProfileUrl = $scope.library.owner && routeService.getProfileUrl($scope.library.owner.username);
    });
    $scope.$on('$destroy', deregisterLibraryChip);

    var deregisterUpdateSearchText = $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams) {
      if ((toState.name === 'library.search') || (toState.name === 'search')) {
        $scope.search.text = toParams.q;
      } else {
        $scope.search.text = '';
      }
    });
    $scope.$on('$destroy', deregisterUpdateSearchText);

    var deregisterAddKeep = $rootScope.$on('triggerAddKeep', function (e, library) {
      $scope.addKeeps(library);
    });
    $scope.$on('$destroy', deregisterAddKeep);

    $scope.focusInput = function () {
      $scope.isFocused = true;
    };
    $scope.blurInput = function () {
      $scope.isFocused = false;
    };

    $scope.clearLibraryName = function () {
      $scope.search.showName = false;
      $scope.library = {};
      $scope.changeSearchInput();
    };

    $scope.search.text = $state.params.q || '';
    $scope.changeSearchInput = _.debounce(function () {
      $timeout(function () {
        if ($location.path() === '/find') {
          $location.search('q', $scope.search.text).replace(); // this keeps any existing URL params
        } else if ($scope.library && $scope.library.url) {
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
      });
    }, 250);

    $scope.onSearchBarClicked = function () {
      if ($scope.library && $scope.library.id) {
        $rootScope.$emit('trackLibraryEvent', 'click', {
          action: 'clickedSearchBar'
        });
      }
    };

    $scope.clearInput = function () {
      $scope.search.text = '';
      $scope.changeSearchInput();
    };

    var KEY_ESC = 27, KEY_DEL = 8;
    $scope.onKeydown = function (e) {
      switch (e.keyCode) {
        case KEY_DEL:
          if ($scope.search.text === '') {
            $scope.clearLibraryName();
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

    friendService.getRequests();
    $scope.friendRequests = friendService.requests;

    $scope.userProfileUrl = routeService.getProfileUrl($scope.me.username);
    $scope.logoutUrl = routeService.logoutUrl;
  }
]);
