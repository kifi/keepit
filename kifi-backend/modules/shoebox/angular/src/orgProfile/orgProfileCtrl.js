'use strict';

angular.module('kifi')

.controller('OrgProfileCtrl', [
  '$window', '$scope', 'profile',
  function ($window, $scope, profile) {
    $window.document.title = profile.name + ' • Kifi';
    $scope.profile = _.cloneDeep(profile);
    $scope.membership = _.cloneDeep(profile.membershipInfo);
  }
]);
