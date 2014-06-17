'use strict';

angular.module('kifi.layout.header', ['kifi.profileService'])

.controller('HeaderCtrl', [
  '$scope', '$rootElement', '$rootScope', '$document', 'profileService', 'friendService', '$location', 'util', 'keyIndices',
  function ($scope, $rootElement, $rootScope, $document, profileService, friendService, $location, util, keyIndices) {

    $scope.toggleMenu = function () {
      $rootElement.toggleClass('kf-sidebar-active');
    };

    $scope.me = profileService.me;
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
      $location.path('/friends');
    };

    $scope.navigateToInvite = function () {
      $location.path('/invite');
    };
  }
]);
