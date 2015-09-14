'use strict';

angular.module('kifi')

.controller('OrgProfileCreateCtrl', [
  '$scope', 'orgProfileService', '$location', 'profileService',
  function($scope, orgProfileService, $location, profileService) {
    $scope.orgName = '';
    $scope.orgSlug = ''; // Not yet implemented.
    if (profileService.me.experiments.indexOf('organization') > -1) {
      $scope.org_experiment = true;
    }
    $scope.createOrg = function() {
      orgProfileService.createOrg(this.orgName).then(function(handle) {
        $location.url('/' + handle);
      }.bind($scope));
    };
  }
]);
