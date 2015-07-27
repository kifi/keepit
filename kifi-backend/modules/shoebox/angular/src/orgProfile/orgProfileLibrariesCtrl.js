'use strict';

angular.module('kifi')

.controller('OrgProfileLibrariesCtrl', [
  '$scope', 'profile', 'profileService', 'orgProfileService', 'modalService',
  function ($scope, profile, profileService, orgProfileService, modalService) {
    var organization = profile;

    $scope.libraries = [];

    $scope.openCreateLibrary = function () {
      modalService.open({
        template: 'libraries/manageLibraryModal.tpl.html',
        modalData: {
          organization: organization,
          returnAction: function (newLibrary) {
            // Add new library to right behind the two system libraries.
            ($scope.libraries || []).splice(2, 0, newLibrary);
          }
        }
      });
    };

    orgProfileService
      .getOrgLibraries(organization.id)
      .then(function (libraryData) {
        $scope.libraries = libraryData.libraries;
      });
  }
]);
