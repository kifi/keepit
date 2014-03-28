'use strict';

angular.module('kifi.friends.friendCard', [])


.directive('kfFriendCard', [function () {
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
      scope.mainImage = '//djty7jcqog9qu.cloudfront.net/users/' + friend.id + '/pics/200/' + friend.pictureName;
      scope.friendCount = friend.friendCount;
      scope.unfriended = friend.unfriended;
      scope.searchFriend = friend.searchFriend;
    }
  };
}]);
