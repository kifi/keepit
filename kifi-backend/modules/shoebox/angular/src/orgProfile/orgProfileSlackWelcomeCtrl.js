'use strict';

angular.module('kifi')

.controller('OrgProfileSlackWelcomeCtrl', [
  '$scope', '$stateParams', 'userProfileActionService',
  function ($scope, $stateParams, userProfileActionService) {
    $scope.user = null;

    userProfileActionService
    .getUsers([ $stateParams.userId ])
    .then(function (usersData) {
      $scope.user = usersData.users[0];
    });
  }
]);
