'use strict';

angular.module('kifi')

.controller('UserProfileConnectionsCtrl', [
  '$scope', '$stateParams', 'userProfileActionService',
  function ($scope, $stateParams, userProfileActionService) {
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
        Array.prototype.push.apply($scope.users, data.users);
        remainingUserIds = _.difference(remainingUserIds, requestedIds);
        loading = false;
      });
    };

    $scope.hasMoreConnections = function () {
      return !$scope.users || !remainingUserIds || remainingUserIds.length;
    };

    userProfileActionService.getConnections($stateParams.username, fetchPageSize).then(function (data) {
      $scope.users = data.invitations ? data.invitations.concat(data.users) : data.users;
      remainingUserIds = data.ids;
      loading = false;
    });
  }
]);
