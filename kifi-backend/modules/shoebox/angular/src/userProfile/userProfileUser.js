'use strict';

angular.module('kifi')

.directive('kfUserProfileUser', [
  'modalService',
  function (modalService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        user: '=kfUserProfileUser'
      },
      templateUrl: 'userProfile/userProfileUser.tpl.html',
      link: function (scope) {
        scope.showMutualConnections = function () {
          var person = _.assign(scope.user, 'id', 'username', 'pictureName');
          person.fullName = scope.user.firstName + ' ' + scope.user.lastName;
          person.numMutualFriends = scope.user.mConnections;
          person.mutualFriends = [];  // TODO
          modalService.open({
            template: 'friends/seeMutualFriendsModal.tpl.html',
            modalData: person
          });
        };
      }
    };
  }
]);
