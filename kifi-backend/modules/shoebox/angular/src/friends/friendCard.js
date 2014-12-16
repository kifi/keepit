'use strict';

angular.module('kifi')


.directive('kfFriendCard', [
  '$log', 'friendService', 'modalService',
  function ($log, friendService, modalService) {
    return {
      scope: {
        'friend': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/friendCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        var friend = scope.friend();
        scope.name = friend.firstName + ' ' + friend.lastName;
        scope.firstName = friend.firstName;
        scope.mainImage = friendService.getPictureUrlForUser(friend);
        scope.friendCount = friend.friendCount;
        scope.unfriended = friend.unfriended;
        scope.searchFriend = friend.searchFriend;

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
