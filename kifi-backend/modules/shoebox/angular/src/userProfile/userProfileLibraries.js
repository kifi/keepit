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
      $scope.libraries = null;
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
      }),

      $rootScope.$on('openCreateLibrary', function () {
        $scope.openCreateLibrary();
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
            $scope.loaded = true;
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

    $scope.openCreateLibrary = function () {
      function getOffsetLeft(element) {
        var offset = element && element.offset && element.offset();
        var offsetLeft = offset && offset.left;
        return offsetLeft || 0;
      }
      function addNewLibAnimationClass(newLibrary) {
        // If the second system library card is under the create-library card,
        // then there are two cards across and the new library will be
        // below and across from the create-library card.
        var spaceBetween = Math.abs(
          getOffsetLeft(angular.element('.kf-upl-create-card')) -
          getOffsetLeft(angular.element('.kf-upl-lib-card').eq(1))
        );
        if (spaceBetween < 10) {
          newLibrary.justAddedBelowAcross = true;
        } else {
          // Otherwise, there are three cards across and the new library will be
          // directly below the create-library-card.
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

    if ($stateParams.openCreateLibrary) {
      $scope.openCreateLibrary();
    }

    resetAndFetchLibraries();
  }
]);
