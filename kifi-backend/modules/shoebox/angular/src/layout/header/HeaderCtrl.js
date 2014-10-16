'use strict';

angular.module('kifi')

.controller('HeaderCtrl', [
  '$scope', '$window', '$rootElement', '$rootScope', '$document', 'profileService', 'friendService',
    '$location', 'util', 'keyIndices', 'modalService', 'libraryService', '$timeout',
  function ($scope, $window, $rootElement, $rootScope, $document, profileService, friendService,
    $location, util, keyIndices, modalService, libraryService, $timeout) {

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
    $scope.search = { text: '', library: {}, showName: true};

    $rootScope.$on('libraryUrl', function (e, library) {
      $scope.search.library = library;
      if ($scope.search.library.id) {
        $scope.search.showName = true;
      } else {
        $scope.search.showName = false;
      }
    });


    $scope.focusInput = function () {
      $scope.isFocused = true;
    };
    $scope.blurInput = function () {
      $scope.isFocused = false;
    };

    $scope.clearLibraryName = function () {
      $scope.search.showName = false;
    };

    $scope.changeSearchInput = _.debounce(function () {
      if ($scope.search.text === '') {
        $timeout($scope.clearInput(), 0);
      }
      // manageTagService.search($scope.filter.name).then(function (tags) {
      //   $scope.tagsToShow = localSortTags(tags);
      //   return $scope.tagsToShow;
      // });
    }, 200);

    $scope.clearInput = function () {
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

    $scope.clearInput = function () {
      $scope.search.text = '';
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
