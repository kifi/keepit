'use strict';

angular.module('kifi.layout.header', ['kifi.profileService'])

.controller('HeaderCtrl', [
  '$scope', '$window', '$rootElement', '$rootScope', '$document', 'profileService', 'friendService', '$location', 'util', 'keyIndices',
  function ($scope, $window, $rootElement, $rootScope, $document, profileService, friendService, $location, util, keyIndices) {

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

    $scope.me = profileService.me;
    $scope.me.picUrl = '//www.kifi.com/assets/img/ghost.200.png';
    profileService.getMe();

    $scope.isActive = function (path) {
      var loc = $location.path();
      return loc === path || util.startsWith(loc, path + '/');
    };

    $scope.logout = function () {
      profileService.logout();
    };

    $scope.addKeeps = function () {
      $rootScope.$emit('showGlobalModal', 'addKeeps');
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

  }
]);
