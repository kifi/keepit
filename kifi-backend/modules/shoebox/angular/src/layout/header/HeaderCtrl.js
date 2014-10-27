'use strict';

angular.module('kifi')

.controller('HeaderCtrl', [
  '$scope', '$window', '$rootElement', '$rootScope', '$document', 'profileService', 'friendService',
    '$location', 'util', 'keyIndices', 'modalService', 'libraryService', '$timeout', 'searchActionService', '$routeParams',
  function ($scope, $window, $rootElement, $rootScope, $document, profileService, friendService,
    $location, util, keyIndices, modalService, libraryService, $timeout, searchActionService, $routeParams) {

    $scope.toggleMenu = function () {
      $rootElement.find('html').toggleClass('kf-sidebar-active');
    };

    $window.addEventListener('message', function (event) {
      if (event.data === 'show_left_column') {  // for guide
        $scope.$apply(function () {
          $rootElement.find('html').addClass('kf-sidebar-active');
        });
      }
    });

    $scope.isFocused = false;
    $scope.library = {};
    $scope.search = { text: '', showName: false };
    $scope.stayInLibraryPath = '';

    // Temp callout method. Remove after most users know about libraries. (Oct 26 2014)
    var calloutName = 'tag_callout_shown';
    $scope.showCallout = function () {
      return !profileService.prefs[calloutName];
    };
    $scope.closeCallout = function () {
      var save = {};
      save[calloutName] = true;
      profileService.prefs[calloutName] = true;
      profileService.savePrefs(save);
    };

    //
    // Watchers & Listeners
    //
    $rootScope.$on('libraryUrl', function (e, library) {
      $scope.library = library;
      $scope.search.text = '';
      if ($scope.library.id) {
        $scope.search.showName = true;
        $scope.stayInLibraryPath = $scope.library.url;
      } else {
        $scope.clearLibraryName();
      }
    });

    $scope.$on('$routeChangeSuccess', function (event, current) {
      if (current.params.q) {
        $scope.search.text = current.params.q;
      }
    });

    $rootScope.$on('triggerAddKeep', function () {
      $scope.addKeeps();
    });

    $scope.focusInput = function () {
      $scope.isFocused = true;
    };
    $scope.blurInput = function () {
      $scope.isFocused = false;
    };

    $scope.clearLibraryName = function () {
      $scope.search.showName = false;
      $scope.stayInLibraryPath = '';
    };

    var query = $routeParams.q || '';
    $scope.changeSearchInput = _.debounce(function () {
      if ($scope.search.text === '') {
        if ($scope.stayInLibraryPath !== '') {
          $timeout(function() {
            $location.url($scope.stayInLibraryPath);
          }, 0);
        }
        $scope.clearInput();
      } else {
        query = $scope.search.text;
        $timeout(function() {
          $location.url('/find?q=' + query + '&f=' + 'm');
        }, 0);
      }
    }, 200);

    $scope.clearInput = function () {
      if ($scope.stayInLibraryPath !== '') {
        $timeout(function () {
          $location.url($scope.stayInLibraryPath);
        }, 0);
      }
      $scope.search.text = '';
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

    $scope.librariesEnabled = false;
    $scope.$watch(function () {
      return libraryService.isAllowed();
    }, function (newVal) {
      $scope.librariesEnabled = newVal;
    });

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
