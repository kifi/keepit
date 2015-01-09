'use strict';

angular.module('kifi')

.controller('UserProfileCtrl', [
  '$scope', '$analytics', '$location', '$rootScope', '$state', '$stateParams', '$timeout', '$window',
  'env', 'inviteService', 'keepWhoService', 'profileService', 'userProfileActionService', 'modalService', 'libraryService',
  function ($scope, $analytics, $location, $rootScope, $state, $stateParams, $timeout, $window,
            env, inviteService, keepWhoService, profileService, userProfileActionService, modalService, libraryService) {
    //
    // Configs.
    //
    var userNavLinksConfig = [
      { name: 'Libraries', /*routeState: 'userProfile.libraries.own',*/ countFieldName: 'numLibraries' },  // routeState is V2
      { name: 'Keeps', countFieldName: 'numKeeps' }  // V1 only

      /*
       * V2
       */
      // { name: 'Friends', routeState: 'userProfile.friends', countFieldName: 'numFriends' },
      // { name: 'Followers', routeState: 'userProfile.followers', countFieldName: 'numFollowers' },
      // { name: 'Helped', routeState: 'userProfile.helped', countFieldName: 'numRekeeps' }
    ];

    var kifiCuratorUsernames = [
      'kifi',
      'kifi-editorial',
      'kifi-eng'
    ];


    //
    // Internal data.
    //
    var ignoreClick = {
      'connect': true
    };
    var $connectEl = null;
    var connectElSelector = '.kf-user-profile-connect';


    //
    // Scope data.
    //
    $scope.userProfileRootUrl = env.origin + '/' + $stateParams.username;
    $scope.profile = null;
    $scope.userLoggedIn = false;
    $scope.viewingOwnProfile = false;
    $scope.canConnectToUser = false;
    $scope.userNavLinks = [];
    $scope.optionalAction = null;
    $scope.isKifiCurator = false;


    //
    // Internal functions.
    //
    function init() {
      // Logged in users who are in the "profiles_beta" experiment and logged out users
      // who have the "upb" query parameter can see this page.
      // Otherwise, redirect to home.
      if ($rootScope.userLoggedIn &&
          !(profileService.me.experiments && profileService.me.experiments.indexOf('profiles_beta') > -1)) {
        $state.go('home');
      }
      if (!$rootScope.userLoggedIn && !$stateParams.upb) {
        $state.go('home');
        $window.location = '/';
      }
      $rootScope.$emit('libraryUrl', {});

      var username = $stateParams.username;
      $scope.isKifiCurator = isKifiCurator(username);

      userProfileActionService.getProfile(username).then(function (profile) {
        setTitle(profile);
        initProfile(profile);
        initViewingUserStatus();
        initUserNavLinks();

        // This function should be called last because some of the attributes
        // that we're tracking are initialized by the above functions.
        trackPageView();
      });
    }

    function isKifiCurator(username) {
      return kifiCuratorUsernames.indexOf(username) !== -1;
    }

    function setTitle(profile) {
      $window.document.title = profile.firstName + ' ' + profile.lastName + ' • Kifi' ;
    }

    function initProfile(profile) {
      $scope.profile = _.cloneDeep(profile);
      $scope.profile.picUrl = keepWhoService.getPicUrl($scope.profile, 200);

      // User id from profile is needed for connection.
      // Use timeout to wait until the connect element is added to the DOM with ng-if.
      $timeout(enableConnectClick);
    }

    function initViewingUserStatus() {
      $scope.userLoggedIn = $rootScope.userLoggedIn;
      $scope.viewingOwnProfile = $scope.profile.id === profileService.me.id;
      $scope.canConnectToUser = $scope.userLoggedIn && !$scope.viewingOwnProfile && !$scope.profile.friendsWith;
      $scope.optionalAction = ($scope.viewingOwnProfile ? 'settings' : false) ||
                              ($scope.canConnectToUser ? 'connect' : false);
    }

    function initUserNavLinks() {
      $scope.userNavLinks = _.map(userNavLinksConfig, function (config) {
        return {
          name: config.name,
          count: $scope.profile[config.countFieldName],
          routeState: config.routeState
        };
      });
    }

    function trackPageView() {
      return;

      /* TODO(yiping): Uncomment this after tracking review with product.
      var url = $analytics.settings.pageTracking.basePath + $location.url();

      var profilePageTrackAttributes = {
        type: 'profile',
        profileOwnerUserId: $scope.profile.id,
        profileOwnedBy: $scope.viewingOwnProfile ? 'viewer' : ($scope.profile.friendsWith ? 'viewersFriend' : 'other'),
        libraryCount: $scope.profile.numLibraries
      };

      $analytics.pageTrack(url, profilePageTrackAttributes);
      */
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

    function enableConnectClick() {
      ignoreClick.connect = false;
      $connectEl = $connectEl || angular.element(connectElSelector);
      $connectEl.attr('href', 'javascript:');  // jshint ignore:line
    }

    function disableConnectClick() {
      ignoreClick.connect = true;
      $connectEl = $connectEl || angular.element(connectElSelector);
      $connectEl.removeAttr('href');
    }

    //
    // Scope methods.
    //
    $scope.connect = function () {
      if (ignoreClick.connect) {
        return;
      }

      disableConnectClick();
      $connectEl = $connectEl || angular.element(connectElSelector);
      $connectEl.text('SENDING REQUEST...');

      inviteService.friendRequest($scope.profile.id).then(function () {
        $connectEl.text('SENT!');
      });
    };

    $scope.isMyLibrary = function(libraryOwnerId) {
      return $scope.profile && (libraryOwnerId === $scope.profile.id);
    };

    $scope.isHidden = function(library) {
      return library.visibility === 'published' && library.listed === false;
    };

    $scope.openModifyLibrary = function (library) {
      modalService.open({
        template: 'libraries/manageLibraryModal.tpl.html',
        modalData: {
          pane: 'manage',
          library: library,
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
        trackPageView();
      }
    });

    $scope.$on('$destroy', deregister$stateChangeSuccess);


    // Initialize controller.
    init();
  }
])


.controller('UserProfileLibrariesCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', 'routeService', 'keepWhoService', 'profileService', 'userProfileActionService',
  function ($scope, $rootScope, $state, $stateParams, routeService, keepWhoService, profileService, userProfileActionService) {
    var username = $stateParams.username;
    var fetchPageSize = 12;
    var fetchPageNumber = 0;
    var hasMoreLibraries = true;
    var loading = false;

    $scope.libraryType = $state.current.data.libraryType;
    $scope.libraries = null;

    var deregister$stateChangeSuccess = $rootScope.$on('$stateChangeSuccess', function (event, toState) {
      if (/^userProfile\.libraries\./.test(toState.name)) {
        $scope.libraryType = toState.data.libraryType;
        refetchLibraries();
      }
    });
    $scope.$on('$destroy', deregister$stateChangeSuccess);

    $scope.fetchLibraries = function () {
      if (loading) {
        return;
      }
      loading = true;

      var filter = $scope.libraryType;
      userProfileActionService.getLibraries(username, filter, fetchPageNumber, fetchPageSize).then(function (data) {
        if ($scope.libraryType === filter) {
          hasMoreLibraries = data[filter].length === fetchPageSize;

          var owner = filter === 'own' ? _.extend({username: username}, $scope.profile) : null;
          $scope.libraries = ($scope.libraries || []).concat(data[filter].map(augmentLibrary.bind(null, owner)));

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

    function resetFetchState() {
      $scope.libraries = null;
      fetchPageNumber = 0;
      hasMoreLibraries = true;
      loading = false;
    }

    function refetchLibraries() {
      resetFetchState();
      $scope.fetchLibraries();
    }

    function augmentLibrary(owner, lib) {
      owner = lib.owner || owner;
      lib.path = '/' + owner.username + '/' + lib.slug;
      lib.owner = owner;
      lib.ownerPicUrl = keepWhoService.getPicUrl(owner, 200);
      lib.ownerProfileUrl = routeService.getProfileUrl(owner.username);
      lib.system = /^system_/.test(lib.kind);
      lib.imageUrl = lib.image ? routeService.libraryImageUrl(lib.image.path) : null;
      lib.followers.forEach(function (user) {
        user.picUrl = keepWhoService.getPicUrl(user, 100);
        user.profileUrl = routeService.getProfileUrl(user.username);
      });
      return lib;
    }
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
