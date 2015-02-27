'use strict';

angular.module('kifi')

.directive('kfFriendCard', [
  '$log', 'friendService', 'modalService', 'routeService',
  function ($log, friendService, modalService, routeService) {
    return {
      scope: {
        'getFriend': '&friend'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/friendCard.tpl.html',
      link: function (scope) {
        scope.friend = scope.getFriend();
        scope.friendProfileUrl = routeService.getProfileUrl(scope.friend.username);

        scope.unfriend = function () {
          modalService.open({
            template: 'friends/unfriendConfirmModal.tpl.html',
            scope: scope
          });
        };

        scope.reallyUnfriend = function () {
          friendService.unfriend(friend.id);
        };

        scope.unsearchfriend = function () {
          friendService.unSearchFriend(friend.id);
        };

        scope.researchfriend = function () {
          friendService.reSearchFriend(friend.id);
        };

      }
    };
  }
]);
