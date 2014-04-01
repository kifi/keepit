'use strict';

angular.module('kifi.friends.friendRequestCard', [])


.directive('kfFriendRequestCard', ['$log', 'friendService', function ($log, friendService) {
  return {
    scope: {
      'request': '&'
    },
    replace: true,
    restrict: 'A',
    templateUrl: 'friends/friendRequestCard.tpl.html',
    link: function (scope/*, element, attrs*/) {
      var friend = scope.request();
      scope.name = friend.firstName + ' ' + friend.lastName;
      scope.mainImage = '//djty7jcqog9qu.cloudfront.net/users/' + friend.id + '/pics/200/' + friend.pictureName;

      scope.accept = function () {
        friendService.acceptRequest(friend.id);
      };

      scope.ignore = function () {
        friendService.ignoreRequest(friend.id);
      };
    }
  };
}]);
