'use strict';

angular.module('kifi')

.controller('OrgProfileCreateCtrl', [
  '$scope', '$analytics', '$timeout', 'orgProfileService', '$state', 'profileService', 'modalService',
  function($scope, $analytics, $timeout, orgProfileService, $state, profileService, modalService) {
    $scope.orgName = '';
    $scope.orgSlug = ''; // Not yet implemented.
    $scope.disableCreate = false;

    $scope.createOrg = function() {
      $scope.disableCreate = true;

      orgProfileService
      .createOrg(this.orgName)
      .then(function(handle) {
        profileService.fetchMe(); // update the me object
        $state.go('orgProfile.libraries', { handle: handle, openInviteModal: true, addMany: true  });
      })
      ['catch'](function () {
        modalService.openGenericErrorModal();
        $scope.disableCreate = false;
      });
    };

    $timeout(function () {
      $analytics.eventTrack('user_viewed_page', {
        type: 'createTeam'
      });
    });
  }
]);
