'use strict';

angular.module('kifi')

.directive('kfUserProfileUser', [
  'modalService', 'profileService', 'userProfileActionService',
  function (modalService, profileService, userProfileActionService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        user: '=kfUserProfileUser'
      },
      templateUrl: 'userProfile/userProfileUser.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;

        scope.showMutualConnections = function () {
          userProfileActionService.getMutualConnections(scope.user.id).then(function (data) {
            var person = _.assign(scope.user, 'id', 'username', 'pictureName');
            person.fullName = scope.user.firstName + ' ' + scope.user.lastName;
            person.numMutualFriends = scope.user.mConnections;
            person.mutualFriends = data.users;
            modalService.open({
              template: 'friends/seeMutualFriendsModal.tpl.html',
              modalData: person
            });
          });
        };
      }
    };
  }
]);
