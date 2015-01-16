'use strict';

angular.module('kifi')

.controller('UserProfileCtrl', [
  '$scope', '$analytics', '$location', '$rootScope', '$state', '$stateParams', '$window',
  'env', 'inviteService', 'keepWhoService', 'originTrackingService', 'profileService', 'userProfileActionService',
  function ($scope, $analytics, $location, $rootScope, $state, $stateParams, $window,
            env, inviteService, keepWhoService, originTrackingService, profileService, userProfileActionService) {

    //
    // Internal data.
    //

    // Mapping of library type to origin contexts for tracking.
    var originContexts = {
      'own': 'MyLibraries',
      'following': 'FollowedLibraries',
      'invited': 'InvitedLibraries'
    };


    //
    // Scope data.
    //
    $scope.userProfileStatus = null;
    $scope.userProfileRootUrl = env.origin + '/' + $stateParams.username;
    $scope.profile = null;
    $scope.userLoggedIn = false;
    $scope.viewingOwnProfile = false;


    //
    // Internal functions.
    //
    function init() {
      $rootScope.$emit('libraryUrl', {});
      var pageOrigin = originTrackingService.getAndClear();
      var username = $stateParams.username;

      userProfileActionService.getProfile(username).then(function (profile) {
        $scope.userProfileStatus = 'found';

        setTitle(profile);
        initProfile(profile);
        initViewingUserStatus();

        // This function should be called last because some of the attributes
        // that we're tracking are initialized by the above functions.
        trackPageView(pageOrigin);
      })['catch'](function () {
        $scope.userProfileStatus = 'not-found';
      });

      $scope.currentPageOrigin = getCurrentPageOrigin();
    }

    function getCurrentPageOrigin() {
      var originContext = originContexts[$state.current.data.libraryType];
      return 'profilePage' +  (originContext ? '.' + originContext : '');
    }

    function setTitle(profile) {
      $window.document.title = profile.firstName + ' ' + profile.lastName + ' • Kifi' ;
    }

    function initProfile(profile) {
      $scope.profile = _.cloneDeep(profile);
      $scope.profile.picUrl = keepWhoService.getPicUrl($scope.profile, 200);
    }

    function initViewingUserStatus() {
      $scope.viewingOwnProfile = $scope.profile.id === profileService.me.id;
    }

    function trackPageView(pageOrigin) {
      var url = $analytics.settings.pageTracking.basePath + $location.url();
      var originsArray = (pageOrigin && pageOrigin.split('/')) || [];

      var profilePageTrackAttributes = {
        type: 'userProfile',
        profileOwnerUserId: $scope.profile.id,
        profileOwnedBy: $scope.viewingOwnProfile ? 'viewer' : ($scope.profile.isFriend ? 'viewersFriend' : 'other'),
        origin: originsArray[0] || '',
        subOrigin: originsArray[1] || '',
        libraryCount: $scope.profile.numLibraries
      };

      $analytics.pageTrack(url, profilePageTrackAttributes);
    }

    function trackPageClick(/* attributes */) {
      return;

      /* TODO(yiping): Uncomment this after tracking review with product.
      var profileEventTrackAttributes = _.extend(attributes || {}, {
        type: 'profile',
        profileOwnedBy: $scope.viewingOwnProfile ? 'viewer' : ($scope.profile.friendsWith ? 'viewersFriend' : 'other')
      });

      $analytics.eventTrack('user_clicked_page', profileEventTrackAttributes);
      */
    }


    //
    // Scope methods.
    //
    $scope.trackUplCardClick = function (lib) {
      trackPageClick({
        action: 'clickedLibrary',
        libraryOwnerUserId: lib.owner.id,

        // lib.owner.friendsWith needs backend support.
        libraryOwnedBy: lib.owner.id === profileService.me.id ? 'viewer' : (lib.owner.friendsWith ? 'viewersFriend' : 'other')
      });
    };

    $scope.trackProfileClick = function () {
      trackPageClick({
        action: 'clickedProfile'
      });
    };


    //
    // Watches and listeners.
    //
    var deregister$stateChangeSuccess = $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams, fromState, fromParams) {
      // When routing among the nested states, track page view again.
      if ((/^userProfile/.test(toState.name)) && (/^userProfile/.test(fromState.name)) && (toParams.username === fromParams.username)) {
        trackPageView(originTrackingService.getAndClear());
        $scope.currentPageOrigin = getCurrentPageOrigin();
      }
    });
    $scope.$on('$destroy', deregister$stateChangeSuccess);


    // Initialize controller.
    init();
  }
])


.controller('UserProfileLibrariesCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', '$timeout',
  'routeService', 'keepWhoService', 'profileService', 'userProfileActionService', 'libraryService', 'modalService',
  function ($scope, $rootScope, $state, $stateParams, $timeout,
    routeService, keepWhoService, profileService, userProfileActionService, libraryService, modalService) {
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
      lib.followers.forEach(function (user) {
        user.picUrl = keepWhoService.getPicUrl(user, 100);
        user.profileUrl = routeService.getProfileUrl(user.username);
      });
      if (lib.following == null && following != null) {
        lib.following = following;
      }
      return lib;
    }

    function resetFetchState() {
      $scope.libraries = null;
      fetchPageNumber = 0;
      hasMoreLibraries = true;
      newLibraryIds.length = 0;
      loading = false;
    }

    function removeDeletedLibrary(event, libraryId) {
      _.remove($scope.libraries, { id: libraryId });
    }

    var deregister$stateChangeSuccess = $rootScope.$on('$stateChangeSuccess', function (event, toState) {
      if (/^userProfile\.libraries\./.test(toState.name)) {
        $scope.libraryType = toState.data.libraryType;
        refetchLibraries();
      }
    });
    $scope.$on('$destroy', deregister$stateChangeSuccess);

    var deregisterLibraryDeleted = $rootScope.$on('libraryDeleted', removeDeletedLibrary);
    $scope.$on('$destroy', deregisterLibraryDeleted);

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
            newLibrary.ownerPicUrl = $scope.profile && $scope.profile.picUrl;
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
      modalService.open({
        template: 'libraries/libraryFollowersModal.tpl.html',
        modalData: {
          library: lib,
          currentPageOrigin: $scope.currentPageOrigin
        }
      });
    };

    $scope.onFollowButtonClick = function (lib, $event) {
      $event.target.disabled = true;
      var following = lib.following;
      libraryService[following ? 'leaveLibrary' : 'joinLibrary'](lib.id).then(function () {
        lib.following = !following;
      })['catch'](function () {
        modalService.openGenericErrorModal();
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
