'use strict';

angular.module('kifi.friends.friendCard', [])


.directive('kfFriendCard', [
  '$log',
  function ($log) {
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
        if (friend.firstName[friend.firstName.length - 1] === 's') {
          scope.possesive = friend.firstName + '\'';
        } else {
          scope.possesive = friend.firstName + 's';
        }
        scope.mainImage = '//djty7jcqog9qu.cloudfront.net/users/' + friend.id + '/pics/200/' + friend.pictureName;
        scope.friendCount = friend.friendCount;
        scope.unfriended = friend.unfriended;
        scope.searchFriend = friend.searchFriend;

        scope.unfriend = function () {
          $log.log('Not actually Unfriending ' + scope.name);
        };

        scope.unsearchfriend = function () {
          $log.log('Not actually Unsearchfriending ' + scope.name);
        };

      }
    };
  }
]);
