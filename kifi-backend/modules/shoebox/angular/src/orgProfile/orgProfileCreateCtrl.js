'use strict';

angular.module('kifi')

.controller('OrgProfileCreateCtrl', [
  '$scope', 'orgProfileService', 'net',
  function($scope, orgProfileService, net) {
    $scope.orgName = '';
    $scope.createOrg = function() {
      net.createOrg({name: $scope.orgName}).then(function(data) {
        debugger;
        alert('org created');
      });
    };
  }
]);
