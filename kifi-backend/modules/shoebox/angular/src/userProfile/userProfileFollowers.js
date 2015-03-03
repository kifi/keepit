'use strict';

angular.module('kifi')

.controller('UserProfileFollowersCtrl', [
  '$scope', '$stateParams', 'userProfileActionService',
  function ($scope, $stateParams, userProfileActionService) {
    var users;
    var remainingUserIds;
    var fetchPageSize = 9;
    var loading = true;

    $scope.fetchFollowers = function () {
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

    $scope.hasMoreFollowers = function () {
      return !users || !remainingUserIds || remainingUserIds.length;
    };

    userProfileActionService.getFollowers($stateParams.username, fetchPageSize).then(function (data) {
      $scope.users = users = data.users;
      remainingUserIds = data.ids;
      loading = false;
    });
  }
]);
