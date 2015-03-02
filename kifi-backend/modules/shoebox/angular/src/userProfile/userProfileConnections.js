'use strict';

angular.module('kifi')

.controller('UserProfileConnectionsCtrl', [
  '$scope', '$stateParams', '$q', 'userProfileActionService', 'modalService',
  function ($scope, $stateParams, $q, userProfileActionService, modalService) {
    var users;
    var remainingUserIds;
    var fetchPageSize = 6;
    var loading = true;

    function getId(o) {
      return o.id;
    }

    $scope.fetchConnections = function () {
      if (loading) {
        return;
      }
      loading = true;

      var requestedIds = remainingUserIds.slice(0, fetchPageSize);
      userProfileActionService.getConnectionsById($stateParams.username, requestedIds).then(function (data) {
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
      if (remainingUserIds) {
        remainingUserIds = _.difference(remainingUserIds, users.map(getId));
      }
      loading = false;
    });

    userProfileActionService.getConnectionIds($stateParams.username).then(function (data) {
      remainingUserIds = users ? _.difference(data, users.map(getId)) : data.ids;
    });
  }
]);
