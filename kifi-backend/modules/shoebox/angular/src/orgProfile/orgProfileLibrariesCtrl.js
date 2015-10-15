'use strict';

angular.module('kifi')

.controller('OrgProfileLibrariesCtrl', [
  '$rootScope', '$scope', '$stateParams', 'profile', 'profileService',
  'orgProfileService', 'modalService', 'Paginator', 'ORG_PERMISSION',
  function ($rootScope, $scope, $stateParams, profile, profileService,
            orgProfileService, modalService, Paginator, ORG_PERMISSION) {
    var organization = profile.organization;
    var libraryLazyLoader = new Paginator(librarySource);
    var newLibraryIds = {};

    function librarySource(pageNumber, pageSize) {
      return orgProfileService
        .getOrgLibraries(organization.id, pageNumber * pageSize, pageSize)
        .then(function (libData) {
          $scope.loaded = true;
          return libData.libraries;
        });
    }

    function resetAndFetchLibraries() {
      $scope.libraries = null;
      newLibraryIds = {};
      libraryLazyLoader.reset();
      $scope.fetchLibraries();
    }

    $scope.libraries = null;

    [
      $rootScope.$on('$stateChangeStart', function (event, toState, toParams, fromState, fromParams) {
        // ui-router wants to navigate to the userOrOrg route because the URL matches,
        // so we tell it not to if we know we're staying in this space.
        if (/^userOrOrg/.test(toState.name) && toParams.handle === fromParams.handle) {
          event.preventDefault();
        }
      }),

      $rootScope.$on('libraryDeleted', function (event, libraryId) {
        _.remove($scope.libraries, {id: libraryId});
      }),

      $rootScope.$on('libraryKeepCountChanged', function (event, libraryId, keepCount) {
        (_.find($scope.libraries, {id: libraryId}) || {}).keepCount = keepCount;
      }),

      $rootScope.$on('openCreateLibrary', function () {
        $scope.openCreateLibrary();
      })

    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    $scope.organization = organization;

    $scope.me = profileService.me;
    $scope.canInvite = $scope.viewer.permissions.indexOf(ORG_PERMISSION.INVITE_MEMBERS) > -1;

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
      if (!$scope.canCreateLibraries) {
        return;
      }

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

    $scope.canCreateLibraries = ($scope.viewer.permissions.indexOf(ORG_PERMISSION.ADD_LIBRARIES) !== -1);

    $scope.shouldShowMoveCard = function () {
      return $scope.canCreateLibraries && ($scope.libraries && $scope.libraries.length < 10) && libraryLazyLoader.hasLoaded();
    };

    $scope.openMoveLibraryHelp = function () {
      modalService.open({
        template: 'common/modal/videoModal.tpl.html',
        modalData: {
          youtubeId: 'ixAmggSbYmg',
          title: 'Moving Libraries'
        }
      });
    };
    $rootScope.$emit('trackOrgProfileEvent', 'view', { type: 'orgLibraries'});

    if ($stateParams.openCreateLibrary) {
      $scope.openCreateLibrary();
    }

    resetAndFetchLibraries();
  }
]);
