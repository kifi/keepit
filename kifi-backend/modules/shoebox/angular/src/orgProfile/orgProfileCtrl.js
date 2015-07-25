'use strict';

angular.module('kifi')

.controller('OrgProfileCtrl', [
  '$window', '$scope', 'profile',
  function ($window, $scope, profile) {
    $window.document.title = profile.organizationInfo.name + ' • Kifi';
    $scope.profile = _.cloneDeep(profile.organizationInfo);
    $scope.membership = _.cloneDeep(profile.membershipInfo);
  }
]);
