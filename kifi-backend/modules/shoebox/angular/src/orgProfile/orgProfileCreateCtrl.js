'use strict';

angular.module('kifi')

.controller('OrgProfileCreateCtrl', [
  '$scope', 'orgProfileService', '$state', 'profileService', 'modalService',
  function($scope, orgProfileService, $state, profileService, modalService) {
    $scope.orgName = '';
    $scope.orgSlug = ''; // Not yet implemented.
    $scope.disableCreate = false;

    $scope.createOrg = function() {
      $scope.disableCreate = true;

      orgProfileService
      .createOrg(this.orgName)
      .then(function(handle) {
        profileService.fetchMe(); // update the me object
        $state.go('orgProfile.libraries', { handle: handle });
      })
      ['catch'](function () {
        modalService.openGenericErrorModal();
        $scope.disableCreate = false;
      });
    };
  }
]);
