'use strict';

angular.module('kifi')

.controller('OrgProfileLibrariesCtrl', [
  '$scope', 'net', 'profile', 'profileService', 'modalService',
  function($scope, net, profile, profileService, modalService) {
    var organization = profile;

    $scope.libraries = [];

    $scope.JSON = JSON;

    $scope.openCreateLibrary = function () {
      modalService.open({
        template: 'libraries/manageLibraryModal.tpl.html',
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
