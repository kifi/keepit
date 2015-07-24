'use strict';

angular.module('kifi')

.controller('OrgProfileLibrariesCtrl', [
  '$scope', 'net', 'profile', 'profileService', 'modalService',
  function($scope, net, profile, profileService, modalService) {
    var organization = profile.organizationInfo;

    $scope.libraries = [];

    $scope.openCreateLibrary = function () {
      modalService.open({
        template: 'libraries/manageUserLibraryModal.tpl.html',
        modalData: {
          returnAction: function (newLibrary) {
            // Add new library to right behind the two system libraries.
            ($scope.libraries || []).splice(2, 0, newLibrary);
          }
        }
      });
    };

    net.getOrgLibraries(organization.id).then(function (response) {
      $scope.libraries = response.data.libraries;
    });
  }
]);
