'use strict';

angular.module('kifi')

.controller('UserProfileCtrl', [
  '$scope', '$analytics', '$location', '$rootScope', '$state', '$stateParams', '$window', 'profile',
  'env', 'inviteService', 'keepWhoService', 'originTrackingService', 'profileService',
  'installService', 'modalService', 'initParams',
  function ($scope, $analytics, $location, $rootScope, $state, $stateParams, $window, profile,
            env, inviteService, keepWhoService, originTrackingService, profileService,
            installService, modalService, initParams) {
    //
    // Internal functions.
    //

    function showInstallModal() {
      $scope.platformName = installService.getPlatformName();
      if ($scope.platformName) {
        $scope.thanksVersion = 'installExt';
        $scope.installExtension = installService.triggerInstall;
      } else {
        $scope.thanksVersion = 'notSupported';
      }

      $scope.close = modalService.close;
      modalService.open({
        template: 'signup/thanksForRegisteringModal.tpl.html',
        scope: $scope
      });
    }

    function getCurrentPageOrigin() {
      var originContext = $scope.libraryTypesToNames[$state.current.data.libraryType];
      return 'profilePage' +  (originContext ? '.' + originContext : '');
    }

    function trackPageView() {
      var url = $analytics.settings.pageTracking.basePath + $location.url();
      $analytics.pageTrack(url, originTrackingService.applyAndClear({
        type: $rootScope.userLoggedIn ? 'userProfile' : 'userProfileLanding',
        profileOwnerUserId: $scope.profile.id,
        profileOwnedBy: $scope.viewingOwnProfile ? 'viewer' : ($scope.profile.isFriend ? 'viewersFriend' : 'other'),
        libraryCount: $scope.profile.numLibraries
      }));
    }

    function trackPageClick(attributes) {
      var profileEventTrackAttributes = _.extend(attributes || {}, {
        type: attributes.type || ($rootScope.userLoggedIn ? 'userProfile' : 'userProfileLanding'),
        profileOwnerUserId: $scope.profile.id,
        profileOwnedBy: $scope.viewingOwnProfile ? 'viewer' : ($scope.profile.isFriend ? 'viewersFriend' : 'other')
      });

      $analytics.eventTrack($rootScope.userLoggedIn ? 'user_clicked_page' : 'visitor_clicked_page', profileEventTrackAttributes);
    }


    //
    // Scope methods and data.
    //

    // Mapping of library type to origin contexts for tracking.
    $scope.libraryTypesToNames = {
      'own': 'MyLibraries',
      'following': 'FollowedLibraries',
      'invited': 'InvitedLibraries'
    };

    $scope.trackUplCardClick = function (/* lib */) {

    };

    $scope.trackProfileClick = function () {

    };


    //
    // Watches and listeners.
    //

    var deregister$stateChangeSuccess = $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams, fromState, fromParams) {
      // When routing among the nested states, track page view again.
      if ((/^userProfile/.test(toState.name)) && (/^userProfile/.test(fromState.name)) && (toParams.username === fromParams.username)) {
        trackPageView();
        $scope.currentPageOrigin = getCurrentPageOrigin();
      }
    });
    $scope.$on('$destroy', deregister$stateChangeSuccess);

    var deregisterCurrentLibrary = $rootScope.$on('getCurrentLibrary', function (e, args) {
      args.callback({});
    });
    $scope.$on('$destroy', deregisterCurrentLibrary);

    var deregisterTrackUserProfile = $rootScope.$on('trackUserProfileEvent', function (e, eventType, attributes) {
      if (eventType === 'click') {
        trackPageClick(attributes);
      } else if (eventType === 'view') {
        trackPageView(attributes);
      }
    });
    $scope.$on('$destroy', deregisterTrackUserProfile);

    //
    // Initialize controller.
    //

    $rootScope.$emit('libraryUrl', {});

    $window.document.title = profile.firstName + ' ' + profile.lastName + ' • Kifi';
    $scope.currentPageOrigin = getCurrentPageOrigin();
    $scope.userProfileRootUrl = env.origin + '/' + $stateParams.username;
    $scope.profile = _.cloneDeep(profile);
    $scope.profile.picUrl = keepWhoService.getPicUrl(profile, 200);
    $scope.viewingOwnProfile = profile.id === profileService.me.id;

    trackPageView();

    if (initParams.install === '1' && !installService.installedVersion) {
      showInstallModal();
    }
  }
])


.controller('UserProfileLibrariesCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', '$timeout', '$location',
  'routeService', 'keepWhoService', 'profileService', 'userProfileActionService', 'libraryService', 'modalService', 'platformService', 'signupService',
  function ($scope, $rootScope, $state, $stateParams, $timeout, $location,
    routeService, keepWhoService, profileService, userProfileActionService, libraryService, modalService, platformService, signupService) {
    var username = $stateParams.username;
    var fetchPageSize = 12;
    var fetchPageNumber = 0;
    var hasMoreLibraries = true;
    var loading = false;
    var newLibraryIds = [];

    $scope.libraryType = $state.current.data.libraryType;
    $scope.libraries = null;
    $scope.me = profileService.me;

    function refetchLibraries() {
      resetFetchState();
      $scope.fetchLibraries();
    }

    function augmentLibrary(owner, following, lib) {
      owner = lib.owner || owner;
      lib.path = '/' + owner.username + '/' + lib.slug;
      lib.owner = owner;
      lib.ownerPicUrl = keepWhoService.getPicUrl(owner, 200);
      lib.ownerProfileUrl = routeService.getProfileUrl(owner.username);
      lib.imageUrl = lib.image ? routeService.libraryImageUrl(lib.image.path) : null;
      lib.followers.forEach(augmentFollower);
      if (lib.following == null && following != null) {
        lib.following = following;
      }
      return lib;
    }

    function augmentFollower(user) {
      user.picUrl = keepWhoService.getPicUrl(user, 100);
      user.profileUrl = routeService.getProfileUrl(user.username);
    }

    function resetFetchState() {
      $scope.libraries = null;
      fetchPageNumber = 0;
      hasMoreLibraries = true;
      newLibraryIds.length = 0;
      loading = false;
    }

    [
      $rootScope.$on('$stateChangeSuccess', function (event, toState) {
        if (/^userProfile\.libraries\./.test(toState.name)) {
          $scope.libraryType = toState.data.libraryType;
          refetchLibraries();
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
            augmentFollower(me);
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

    $scope.trackLibraryNav = function (toLibraryType) {
      debugger;

      $rootScope.$emit('trackUserProfileEvent', 'click', {
        'action': 'clicked' + $scope.libraryTypesToNames[toLibraryType]
      });
    };

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
              var followersList = library.followers; // contains picUrl for each follower
              _.assign(library, data.library, {followers: followersList}); // replaces all new data (but data.library.followers does not have picUrl)
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
  }
])


.controller('UserProfilePeopleCtrl', [
  '$scope', '$state',
  function ($scope, $state) {
    $scope.peopleType = $state.current.data.peopleType;
  }
])


.controller('UserProfileKeepsCtrl', [
  '$scope',
  function ($scope) {
    $scope.keepType = 'Helped Rekeep';
  }
]);
