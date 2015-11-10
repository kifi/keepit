'use strict';

angular.module('kifi')

.controller('OrgProfileCreateCtrl', [
  '$scope', '$timeout', 'orgProfileService', '$state', 'profileService', 'modalService',
  function($scope, $timeout, orgProfileService, $state, profileService, modalService) {
    $scope.orgSlug = ''; // Not yet implemented.
    $scope.disableCreate = false;

    $scope.orgName = '';
    $scope.$watch(function () {
      return profileService.prefs.stored_credit_code;
    }, function () {
      if (profileService.prefs.stored_credit_code) {
        $scope.redeemCode = profileService.prefs.stored_credit_code;
      }
    });

    if (!profileService.prefs.company_name) {
      profileService.fetchPrefs().then(function (prefs) {
        if (prefs.company_name && !orgNameExists(prefs.company_name)) {
          $scope.orgName = prefs.company_name;
        }
      });
    } else {
      $scope.orgName = (!orgNameExists(profileService.prefs.company_name) && profileService.prefs.company_name) || '';
    }

    function orgNameExists(companyName) {
      var orgNames = profileService.me.orgs.map(
        function(org) {
          return org.name.toLowerCase();
        }
      );
      return orgNames.indexOf(companyName.toLowerCase()) !== -1;
    }

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
      $scope.$emit('trackOrgProfileEvent', 'view', {
        type: 'createTeam'
      });
    });
  }
]);
