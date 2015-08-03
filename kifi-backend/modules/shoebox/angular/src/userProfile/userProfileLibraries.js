'use strict';

angular.module('kifi')

.controller('UserProfileLibrariesCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', '$timeout', 'Paginator',
  'profileService', 'userProfileActionService', 'modalService', 'userProfilePageNames',
  function ($scope, $rootScope, $state, $stateParams, $timeout, Paginator,
            profileService, userProfileActionService, modalService, userProfilePageNames) {
    var handle = $stateParams.handle;
    var newLibraryIds = {};

    $scope.libraries = null;
    $scope.libraryType = $state.current.name.split('.').pop();

    var libraryLazyLoader = new Paginator();

    function resetAndFetchLibraries() {
      newLibraryIds = {};
      libraryLazyLoader.reset();
      $scope.fetchLibraries();
    }

    [
      $rootScope.$on('$stateChangeSuccess', function (event, toState) {
        if (/^userProfile\.libraries\./.test(toState.name)) {
          $scope.libraryType = toState.name.split('.').pop();
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
      var filter = $scope.libraryType;

      function librarySource(pageNumber, pageSize) {
        return userProfileActionService
          .getLibraries(handle, filter, pageNumber, pageSize)
          .then(function (data) {
            return data[filter];
          });
      }

      libraryLazyLoader.fetch(librarySource).then(function (libs) {
        if ($scope.profile.id === profileService.me.id && filter === 'own' && !_.isEmpty(newLibraryIds)) {
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

    $scope.showInvitedLibraries = function () {
      return $scope.profile && $scope.profile.numInvitedLibraries && $scope.viewingOwnProfile;
    };

    $scope.openCreateLibrary = function () {
      function addNewLibAnimationClass(newLibrary) {
        // If the second system library card is under the create-library card,
        // then there are two cards across and the new library will be
        // below and across from the create-library card.
        if ((Math.abs(angular.element('.kf-upl-create-card').offset().left -
                      angular.element('.kf-upl-lib-card').eq(1).offset().left)) < 10) {
          newLibrary.justAddedBelowAcross = true;
        }
        // Otherwise, there are three cards across and the new library will be
        // directly below the create-library-card.
        else {
          newLibrary.justAddedBelow = true;
        }

        $timeout(function () {
          newLibrary.justAddedBelow = false;
          newLibrary.justAddedBelowAcross = false;
        });
      }

      modalService.open({
        template: 'libraries/manageLibraryModal.tpl.html',
        modalData: {
          createOnly: true,
          returnAction: function (newLibrary) {
            addNewLibAnimationClass(newLibrary);
            newLibraryIds[newLibrary.id] = true;

            // Add new library to right behind the two system libraries.
            ($scope.libraries || []).splice(2, 0, newLibrary);
          }
        }
      });
    };

    $scope.trackLibraryNav = function (toLibraryType) {
      $rootScope.$emit('trackUserProfileEvent', 'click', {
        'action': 'clicked' + userProfilePageNames[toLibraryType]
      });
    };

    resetAndFetchLibraries();
  }
]);
