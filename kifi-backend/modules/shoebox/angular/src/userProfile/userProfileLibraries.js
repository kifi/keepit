'use strict';

angular.module('kifi')

.controller('UserProfileLibrariesCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', '$timeout', '$location',
  'routeService', 'profileService', 'userProfileActionService', 'libraryService', 'modalService', 'platformService', 'signupService',
  function ($scope, $rootScope, $state, $stateParams, $timeout, $location,
    routeService, profileService, userProfileActionService, libraryService, modalService, platformService, signupService) {
    var username = $stateParams.username;
    var fetchPageSize = 12;
    var fetchPageNumber;
    var hasMoreLibraries;
    var loading;
    var newLibraryIds;

    $scope.libraries = null;
    $scope.libraryType = $state.current.name.split('.').pop();
    $scope.me = profileService.me;

    function resetAndFetchLibraries() {
      fetchPageNumber = 0;
      hasMoreLibraries = true;
      loading = false;
      newLibraryIds = [];
      $scope.libraries = null;
      $scope.fetchLibraries();
    }

    function augmentLibrary(owner, following, lib) {
      owner = lib.owner || owner;
      lib.path = '/' + owner.username + '/' + lib.slug;
      lib.owner = owner;
      lib.imageUrl = lib.image ? routeService.libraryImageUrl(lib.image.path) : null;
      if (lib.following == null && following != null) {
        lib.following = following;
      }
      return lib;
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
      $rootScope.$on('libraryJoined', function (event, libraryId) {
        var lib = _.find($scope.libraries, {id: libraryId});
        if (lib && !lib.following) {
          lib.following = true;
          lib.numFollowers++;
          if (lib.followers.length < 3 && profileService.me.pictureName !== '0.jpg') {
            var me = _.pick(profileService.me, 'id', 'firstName', 'lastName', 'pictureName', 'username');
            lib.followers.push(me);
          }
        }
      }),
      $rootScope.$on('libraryLeft', function (event, libraryId) {
        var lib = _.find($scope.libraries, {id: libraryId});
        if (lib && lib.following) {
          lib.following = false;
          lib.numFollowers--;
          _.remove(lib.followers, {id: profileService.me.id});
        }
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
      userProfileActionService.getLibraries(username, filter, fetchPageNumber, fetchPageSize).then(function (data) {
        if ($scope.libraryType === filter) {
          hasMoreLibraries = data[filter].length === fetchPageSize;

          var isMyProfile = $scope.profile.id === $scope.me.id;
          var owner = filter === 'own' ? _.extend({username: username}, $scope.profile) : null;
          var following = isMyProfile ? (filter === 'following' ? true : (filter === 'invited' ? false : null)) : null;

          var filteredLibs = data[filter];
          if (filter === 'own' && isMyProfile && newLibraryIds.length) {
            _.remove(filteredLibs, function (lib) {
              return _.contains(newLibraryIds, lib.id);
            });
          }

          $scope.libraries = ($scope.libraries || []).concat(filteredLibs.map(augmentLibrary.bind(null, owner, following)));

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
            augmentLibrary(null, null, newLibrary);

            addNewLibAnimationClass(newLibrary);
            newLibraryIds.push(newLibrary.id);

            // Add new library to right behind the two system libraries.
            ($scope.libraries || []).splice(2, 0, newLibrary);
          }
        }
      });
    };

    $scope.openModifyLibrary = function (library) {
      modalService.open({
        template: 'libraries/manageLibraryModal.tpl.html',
        modalData: {
          pane: 'manage',
          library: library,
          currentPageOrigin: $scope.currentPageOrigin,
          returnAction: function () {
            libraryService.getLibraryById(library.id, true).then(function (data) {
              _.assign(library, data.library);
              library.listed = data.listed;
              library.path = data.library.url;
            })['catch'](modalService.openGenericErrorModal);
          }
        }
      });
    };

    $scope.openFollowersList = function (lib) {
      if (platformService.isSupportedMobilePlatform()) {
        return;
      }

      modalService.open({
        template: 'libraries/libraryFollowersModal.tpl.html',
        modalData: {
          library: lib,
          currentPageOrigin: $scope.currentPageOrigin
        }
      });
    };

    $scope.onFollowButtonClick = function (lib, $event) {
      if (platformService.isSupportedMobilePlatform()) {
        var url = $location.absUrl();
        platformService.goToAppOrStore(url + (url.indexOf('?') > 0 ? '&' : '?') + 'follow=true');
        return;
      } else if ($rootScope.userLoggedIn === false) {
        return signupService.register({libraryId: lib.id, intent: 'follow', redirectPath: lib.path});
      }
      $event.target.disabled = true;
      libraryService[lib.following ? 'leaveLibrary' : 'joinLibrary'](lib.id)['catch'](function (resp) {
        modalService.openGenericErrorModal({
          modalData: resp.status === 403 && resp.data.error === 'cant_join_nonpublished_library' ? {
            genericErrorMessage: 'Sorry, the owner of this library has made it private. You’ll need an invitation to follow it.'
          } : {}
        });
      })['finally'](function () {
        $event.target.disabled = false;
      });
    };

    $scope.trackLibraryNav = function (toLibraryType) {
      $rootScope.$emit('trackUserProfileEvent', 'click', {
        'action': 'clicked' + $scope.stateSuffixToTrackingName[toLibraryType]
      });
    };

    $scope.trackUplCardClick = function (lib, subAction) {
      $rootScope.$emit('trackUserProfileEvent', 'click', {
        action: 'clickedLibraryCard',
        subAction: subAction,
        libraryName: lib.name,
        libraryOwnerUserName: lib.owner.username,
        libraryId: lib.id,
        libraryOwnerUserId: lib.owner.id,
        profileTab: $scope.stateSuffixToTrackingName[$scope.libraryType]
      });
    };

    resetAndFetchLibraries();
  }
]);
