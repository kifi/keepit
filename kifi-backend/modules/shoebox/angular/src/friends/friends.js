'use strict';

angular.module('kifi.friends', [
  'util',
  'kifi.social',
  'kifi.profileService',
  'kifi.routeService',
  'kifi.invite'
])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/friends', {
      templateUrl: 'friends/friends.tpl.html',
      controller: 'FriendsCtrl'
    });
  }
])

.controller('FriendsCtrl', [
  '$scope', '$window', 'friendService',
  function ($scope, $window, friendService) {
    $window.document.title = 'Kifi • Your Friends on Kifi';

    $scope.friends = friendService.friends;
    friendService.getKifiFriends();

  }
]);

