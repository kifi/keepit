'use strict';

angular.module('kifi')

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/friends', {
      templateUrl: 'friends/friends.tpl.html'
    }).when('/friends/requests', {
      redirectTo: '/friends'
    }).when('/friends/requests/:network', {
      redirectTo: '/friends'
    });
  }
])

.controller('FriendsCtrl', [
  '$scope', '$window', 'friendService', 'socialService',
  function ($scope, $window, friendService, socialService) {
    $window.document.title = 'Kifi • Your Friends on Kifi';

    $scope.$watch(socialService.checkIfRefreshingSocialGraph, function (v) {
      $scope.isRefreshingSocialGraph = v;
    });

    socialService.checkIfUpdatingGraphs(1);

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

    $scope.totalFriends = friendService.totalFriends;

    $scope.friendsScrollDistance = '100%';
    $scope.isFriendsScrollDisabled = function () {
      return !friendService.hasMore();
    };
    $scope.friendsScrollNext = _.throttle(friendService.getMore, 1000);

    $scope.friends = friendService.friends;
    $scope.friendsHasRequested = friendService.friendsHasRequested;

    friendService.getKifiFriends();
    friendService.getRequests();
  }
]);

