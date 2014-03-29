'use strict';

angular.module('kifi.friends.friendRequestCard', [])


.directive('kfFriendRequestCard', ['$log', function ($log) {
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
        $log.log('Not actually accepting friend request from ' + scope.name);
      };

      scope.ignore = function () {
        $log.log('Not actually ignoring friend request from ' + scope.name);
      };
    }
  };
}]);
