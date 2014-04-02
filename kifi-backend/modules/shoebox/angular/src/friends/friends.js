'use strict';

angular.module('kifi.friends', [
  'util',
  'kifi.social',
  'kifi.profileService',
  'kifi.routeService',
  'kifi.invite',
  'angulartics'
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
  '$scope', '$window', 'friendService', '$analytics',
  function ($scope, $window, friendService, $analytics) {
    $window.document.title = 'Kifi • Your Friends on Kifi';

    $scope.requests = friendService.requests;
    var requestsCollapsed = true;
    $scope.requestsToShow = 2;
    $scope.requestsToggleText = 'See all requests';
    $scope.toggleRequestExpansion = function () {
      requestsCollapsed = !requestsCollapsed;
      if (requestsCollapsed) {
        $scope.requestsToShow = 2;
        $scope.requestsToggleText = 'See all requests';
      } else {
        $scope.requestsToShow = $scope.requests.length;
        $scope.requestsToggleText = 'See fewer requests';
      }
    };

    $scope.friends = friendService.friends;
    friendService.getKifiFriends();
    friendService.getRequests();

    $analytics.eventTrack('user_clicked_page', {
      'action': 'addFriends',
      'friends': $scope.friends.size
    });

  }
]);

