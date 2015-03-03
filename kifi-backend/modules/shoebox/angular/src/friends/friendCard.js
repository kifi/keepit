'use strict';

angular.module('kifi')

.directive('kfFriendCard', [
  'friendService', 'modalService',
  function (friendService, modalService) {
    return {
      scope: {
        'getFriend': '&friend'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/friendCard.tpl.html',
      link: function (scope) {
        scope.friend = scope.getFriend();

        scope.unfriend = function () {
          modalService.open({
            template: 'friends/unfriendConfirmModal.tpl.html',
            scope: scope
          });
        };

        scope.reallyUnfriend = function () {
          friendService.unfriend(scope.friend.id);
        };

        scope.unsearchfriend = function () {
          friendService.unSearchFriend(scope.friend.id);
        };

        scope.researchfriend = function () {
          friendService.reSearchFriend(scope.friend.id);
        };

      }
    };
  }
]);
