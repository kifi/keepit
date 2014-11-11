'use strict';

angular.module('kifi')

.controller('HeaderCtrl', [
  '$scope', '$window', '$rootElement', '$rootScope', '$document', 'profileService', 'friendService',
    '$location', 'util', 'keyIndices', 'modalService', '$timeout', 'searchActionService', '$routeParams',
  function ($scope, $window, $rootElement, $rootScope, $document, profileService, friendService,
    $location, util, keyIndices, modalService, $timeout, searchActionService, $routeParams) {

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

      if (!$scope.library.owner.picUrl) {
        $scope.library.owner.picUrl = friendService.getPictureUrlForUser($scope.library.owner);
      }

      if ($scope.library.id) {
        $scope.search.showName = true;
      } else {
        $scope.clearLibraryName();
      }

      $scope.search.text = $routeParams.q || '';
    });
    $scope.$on('$destroy', deregisterLibraryChip);

    $scope.$on('$routeUpdate', function (event, current) {
      if (current.params.q) {
        $scope.search.text = current.params.q;
      }
    });


    var deregisterAddKeep = $rootScope.$on('triggerAddKeep', function () {
      $scope.addKeeps();
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

    $scope.search.text = $routeParams.q || '';
    $scope.changeSearchInput = _.debounce(function () {
      $timeout(function() {
        // We are not already on the search page.
        if ($location.path() !== '/find') {
          if ($scope.library && $scope.library.url) {
            if ($scope.search.text) {
              $location.url($scope.library.url + '/find?q=' + $scope.search.text + '&f=a');
            } else {
              $location.url($scope.library.url);
            }
          } else {
            $location.url('/find?q=' + $scope.search.text);
          }
        }

        // We are already on the search page.
        else {
          $location.search('q', $scope.search.text).replace(); // this keeps any existing URL params
        }
      });
    }, 100, {
      'leading': true
    });

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

    $scope.isActive = function (path) {
      var loc = $location.path();
      return loc === path || util.startsWith(loc, path + '/');
    };

    $scope.logout = function () {
      profileService.logout();
    };

    $scope.addKeeps = function () {
      modalService.open({
        template: 'keeps/addKeepsModal.tpl.html'
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

    $scope.navigateToFriends = function () {
      $location.path('/friends');  // TODO: put directly in <a href="">
    };

    $scope.navigateToInvite = function () {
      $location.path('/invite');  // TODO: put directly in <a href="">
    };

    $scope.navigateToManageTags = function () {
      $location.path('/tags/manage');
    };

  }
]);
