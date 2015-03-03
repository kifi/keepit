'use strict';

angular.module('kifi')

.controller('UserProfileConnectionsCtrl', [
  '$scope', '$stateParams', '$q', 'userProfileActionService', 'modalService',
  function ($scope, $stateParams, $q, userProfileActionService, modalService) {
    var users;
    var remainingUserIds;
    var fetchPageSize = 9;
    var loading = true;

    $scope.fetchConnections = function () {
      if (loading) {
        return;
      }
      loading = true;

      var requestedIds = remainingUserIds.slice(0, fetchPageSize);
      userProfileActionService.getUsers(requestedIds).then(function (data) {
        users.push.apply(users, data.users);
        remainingUserIds = _.difference(remainingUserIds, requestedIds);
        loading = false;
      });
    };

    $scope.hasMoreConnections = function () {
      return !users || !remainingUserIds || remainingUserIds.length;
    };

    $scope.showMutualConnections = function (user) {
      var person = _.assign(user, 'id', 'username', 'pictureName');
      person.fullName = user.firstName + ' ' + user.lastName;
      person.mutualFriends = [];  // TODO
      modalService.open({
        template: 'friends/seeMutualFriendsModal.tpl.html',
        modalData: person
      });
    };

    userProfileActionService.getConnections($stateParams.username, fetchPageSize).then(function (data) {
      $scope.users = users = data.users;
      remainingUserIds = data.ids;
      loading = false;
    });
  }
]);
