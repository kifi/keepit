'use strict';

angular.module('kifi')

.directive('kfContactAdmin', [
  'orgProfileService',
  function (orgProfileService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        getStatus: '&status',
        getState: '&state',
        getParams: '&params'
      },
      templateUrl: 'errors/contactAdmin.tpl.html',
      link: function ($scope) {
        var stateParams = $scope.getParams();

        if ($scope.getStatus() === 403) {
          orgProfileService
          .userOrOrg(stateParams.handle)
          .then(function (userOrOrgData) {
            if (userOrOrgData.type === 'org') {
              $scope.org = userOrOrgData.result.organization;
            } else {
              $scope.user = userOrOrgData.result;
            }
          });
        }
      }
    };
  }
]);
