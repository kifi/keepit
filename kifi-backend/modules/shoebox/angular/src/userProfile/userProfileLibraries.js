'use strict';

angular.module('kifi')

.controller('UserProfileLibrariesCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', '$timeout',
  'profileService', 'userProfileActionService', 'modalService', 'userProfilePageNames',
  function ($scope, $rootScope, $state, $stateParams, $timeout,
            profileService, userProfileActionService, modalService, userProfilePageNames) {
    var handle = $stateParams.handle;
    var fetchPageSize = 12;
    var fetchPageNumber;
    var hasMoreLibraries;
    var loading;
    var newLibraryIds;

    $scope.libraries = null;
    $scope.libraryType = $state.current.name.split('.').pop();

    function resetAndFetchLibraries() {
      fetchPageNumber = 0;
      hasMoreLibraries = true;
      loading = false;
      newLibraryIds = {};
      $scope.libraries = null;
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
      if (loading) {
        return;
      }
      loading = true;

      var filter = $scope.libraryType;
      userProfileActionService.getLibraries(handle, filter, fetchPageNumber, fetchPageSize).then(function (data) {
        if ($scope.libraryType === filter) {
          var libs = data[filter];
          hasMoreLibraries = libs.length === fetchPageSize;  // important to do before filtering below

          if ($scope.profile.id === profileService.me.id && filter === 'own' && !_.isEmpty(newLibraryIds)) {
            _.remove(libs, function (lib) {
              return lib.id in newLibraryIds;
            });
          }

          $scope.libraries = ($scope.libraries || []).concat(libs);

          fetchPageNumber++;
          loading = false;
        }
      });
    };

    $scope.hasMoreLibraries = function () {
      return hasMoreLibraries;
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
