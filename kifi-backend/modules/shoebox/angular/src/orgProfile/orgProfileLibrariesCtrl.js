'use strict';

angular.module('kifi')

.controller('OrgProfileLibrariesCtrl', [
  '$rootScope', '$scope', 'profile', 'profileService', 'orgProfileService', 'modalService', 'Paginator',
  function ($rootScope, $scope, profile, profileService, orgProfileService, modalService, Paginator) {
    var organization = profile.organization;
    var newLibraryIds = {};
    var libraryLazyLoader = new Paginator(librarySource);

    function librarySource(pageNumber, pageSize) {
      return orgProfileService
        .getOrgLibraries(organization.id, pageNumber, pageSize)
        .then(function (libData) {
          return libData.libraries;
        });
    }

    function resetAndFetchLibraries() {
      libraryLazyLoader.reset();
      $scope.fetchLibraries();
    }

    $scope.libraries = [];

    [
      $rootScope.$on('$stateChangeSuccess', function (event, toState) {
        if (/^orgProfile\.libraries\./.test(toState.name)) {
          //$scope.libraryType = toState.name.split('.').pop();
          resetAndFetchLibraries();
        }
      }),
      $rootScope.$on('libraryDeleted', function (event, libraryId) {
        _.remove($scope.libraries, {id: libraryId});
      }),
      $rootScope.$on('libraryKeepCountChanged', function (event, libraryId, keepCount) {
        (_.find($scope.libraries, {id: libraryId}) || {}).keepCount = keepCount;
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    $scope.fetchLibraries = function () {
      libraryLazyLoader
        .fetch()
        .then(function (libs) {
          if (!_.isEmpty(newLibraryIds)) {
            _.remove(libs, function (lib) {
              return lib.id in newLibraryIds;
            });
          }

          $scope.libraries = libs;
        });
    };

    $scope.hasMoreLibraries = function () {
      return libraryLazyLoader.hasMore();
    };

    $scope.openCreateLibrary = function () {
      modalService.open({
        template: 'libraries/manageLibraryModal.tpl.html',
        modalData: {
          organization: organization,
          returnAction: function (newLibrary) {
            newLibraryIds[newLibrary.id] = true;

            // Add new library to right behind the two system libraries.
            ($scope.libraries || []).splice(0, 0, newLibrary);
          }
        }
      });
    };

    resetAndFetchLibraries();
  }
]);
